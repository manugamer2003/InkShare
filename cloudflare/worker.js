// InkShare: scheduled-only notification sender. No public send endpoint.
// Secret: FIREBASE_SERVICE_ACCOUNT (complete JSON). Free plans only.
const PROJECT = "inkshare-7527e";
const ROOT = `projects/${PROJECT}/databases/(default)/documents`;
const API = "https://firestore.googleapis.com/v1/";
let cachedAuth;
const text = new TextEncoder();
const s = v => ({stringValue:String(v)});
const n = v => ({integerValue:String(v)});
const value = (doc,k) => doc.fields?.[k]?.stringValue;
const number = (doc,k) => Number(doc.fields?.[k]?.integerValue || 0);
const encoded = b => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_");

async function auth(env) {
  if(cachedAuth && cachedAuth.until > Date.now()) return cachedAuth.token;
  const account=JSON.parse(env.FIREBASE_SERVICE_ACCOUNT || "{}");
  if(account.project_id!==PROJECT || !account.client_email || !account.private_key) throw new Error("SERVICE_ACCOUNT_CONFIG");
  const now=Math.floor(Date.now()/1000);
  const unsigned=encoded(text.encode(JSON.stringify({alg:"RS256",typ:"JWT"})))+"."+
    encoded(text.encode(JSON.stringify({iss:account.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3600})));
  const pem=account.private_key.replace(/-----[^-]+-----/g,"").replace(/\s/g,"");
  const key=await crypto.subtle.importKey("pkcs8",Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const signature=await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,text.encode(unsigned));
  const response=await fetch("https://oauth2.googleapis.com/token",{method:"POST",body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:unsigned+"."+encoded(signature)})});
  if(!response.ok) throw new Error("GOOGLE_AUTH_"+response.status);
  const result=await response.json();
  cachedAuth={token:result.access_token,until:Date.now()+Math.max(0,Number(result.expires_in)-120)*1000};
  return cachedAuth.token;
}
async function call(token,url,options={}) {
  return fetch(url,{...options,headers:{Authorization:`Bearer ${token}`,"Content-Type":"application/json"},signal:AbortSignal.timeout(15000)});
}
async function patch(token,doc,fields) {
  const query=new URLSearchParams({"currentDocument.updateTime":doc.updateTime});
  for(const key of Object.keys(fields)) query.append("updateMask.fieldPaths",key);
  const result=await call(token,API+doc.name+"?"+query,{method:"PATCH",body:JSON.stringify({fields})});
  if(result.status===409 || result.status===412) return null;
  if(!result.ok) throw new Error("FIRESTORE_WRITE_"+result.status);
  return result.json();
}
export function messageFor(event,id) {
  const kind=value(event,"kind"), outcome=value(event,"outcome"), name=value(event,"actorName");
  if(!["friend","loan"].includes(kind) || !["requested","accepted","rejected"].includes(outcome) || !/^[a-z0-9_]{3,20}$/.test(name||"")) throw new Error("INVALID_EVENT");
  const friend=kind==="friend", requested=outcome==="requested", accepted=outcome==="accepted";
  return {
    recipientUid:value(event,"recipientUid"), eventId:id, entityId:value(event,"requestId"),
    destination:friend?"friends":"loans",
    tab:String(requested?(friend?1:2):accepted?0:(friend?2:3)),
    title:requested?(friend?"Solicitud de amistad":"Solicitud de préstamo"):(friend?"Solicitud de amistad ":"Solicitud de préstamo ")+(accepted?"aceptada":"rechazada"),
    body:requested?(friend?`@${name} quiere añadirte como amigo.`:`@${name} te ha pedido cartas prestadas.`):`@${name} ha ${accepted?"aceptado":"rechazado"} tu solicitud${friend?" de amistad":" de préstamo"}.`
  };
}
async function devices(token,uid) {
  if(!uid || uid.includes("/")) throw new Error("INVALID_RECIPIENT");
  const result=await call(token,API+ROOT+`/users/${encodeURIComponent(uid)}/devices?pageSize=10`);
  if(!result.ok) throw new Error("DEVICE_READ_"+result.status);
  const body=await result.json();
  // Limit work per tick. Normal accounts have only a few phones.
  if(body.nextPageToken) throw new Error("TOO_MANY_DEVICES");
  return body.documents || [];
}
async function sendEvent(token,original) {
  const now=Date.now();
  if(number(original,"leaseUntil")>now || number(original,"retryAfter")>now) return;
  let event=await patch(token,original,{leaseUntil:n(now+120000)});
  if(!event) return;
  try {
    const data=messageFor(event,event.name.split("/").pop());
    const age=now-Date.parse(event.fields.createdAt.timestampValue);
    if(age>86400000) { await patch(token,event,{state:s("expired"),leaseUntil:n(0)});return; }
    // Do not deliver an obsolete incoming invitation after it was resolved or cancelled.
    if(value(event,"outcome")==="requested") {
      const collection=value(event,"kind")==="friend"?"friendRequests":"loanRequests";
      const requestId=value(event,"requestId");
      if(!requestId || requestId.includes("/")) throw new Error("INVALID_REQUEST");
      const result=await call(token,API+ROOT+`/${collection}/${encodeURIComponent(requestId)}`);
      if(result.status===404) {await patch(token,event,{state:s("obsolete"),leaseUntil:n(0)});return;}
      if(!result.ok) throw new Error("REQUEST_READ_"+result.status);
      const request=await result.json();
      if(value(request,"notificationId")!==data.eventId || (collection==="loanRequests" && value(request,"status")!=="pending")) {
        await patch(token,event,{state:s("obsolete"),leaseUntil:n(0)});return;
      }
    }
    const targets=await devices(token,data.recipientUid);
    if(targets.length===0) {
      await patch(token,event,{state:s("pending"),leaseUntil:n(0),retryAfter:n(now+60000),lastError:s("NO_DEVICES")});return;
    }
    for(const device of targets) {
      const registration=value(device,"token");
      if(!registration) continue;
      const response=await call(token,`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`,{
        method:"POST",body:JSON.stringify({message:{token:registration,data,android:{priority:"HIGH",ttl:"86400s"}}})
      });
      if(!response.ok) {
        const error=await response.json().catch(()=>({}));
        const code=error.error?.details?.find(d=>d.errorCode)?.errorCode;
        if(code==="UNREGISTERED") {
          // Preconditions avoid deleting a token refreshed concurrently.
          const query=new URLSearchParams({"currentDocument.updateTime":device.updateTime});
          const removed=await call(token,API+device.name+"?"+query,{method:"DELETE"});
          if(!removed.ok && ![404,409,412].includes(removed.status)) throw new Error("DEVICE_DELETE_"+removed.status);
        } else throw new Error("FCM_"+response.status);
      }
    }
    await patch(token,event,{state:s("sent"),leaseUntil:n(0),sentAt:{timestampValue:new Date().toISOString()}});
  } catch(error) {
    const attempts=number(event,"attempts")+1;
    const code=/^([A-Z_]+[0-9]*)$/.test(error.message)?error.message:"DELIVERY_ERROR";
    await patch(token,event,{state:s(attempts>=8?"failed":"pending"),attempts:n(attempts),lastError:s(code),leaseUntil:n(0),retryAfter:n(Date.now()+Math.min(3600000,60000*2**attempts))});
    // Never log event payloads, device tokens, keys, or OAuth responses.
    console.error("Notification delivery failed", /^([A-Z_]+[0-9]*)$/.test(error.message)?error.message:"DELIVERY_ERROR");
  }
}
export async function run(env) {
  const token=await auth(env);
  const response=await call(token,API+ROOT+":runQuery",{method:"POST",body:JSON.stringify({structuredQuery:{
    from:[{collectionId:"notificationOutbox"}],where:{fieldFilter:{field:{fieldPath:"state"},op:"EQUAL",value:s("pending")}},limit:2
  }})});
  if(!response.ok) throw new Error("OUTBOX_READ_"+response.status);
  const rows=await response.json();
  for(const row of rows) if(row.document) await sendEvent(token,row.document);
}
export default {
  fetch(request) {
    if(request.method!=="GET") return new Response("Method not allowed",{status:405});
    return Response.json({service:"InkShare notifications",version:2,mode:"scheduled",note:"Enviar avisos requiere activar el Cron Trigger y los permisos de Firebase."});
  },
  async scheduled(_controller,env) {
    try { await run(env); }
    catch(error) { console.error("Notification worker failed",/^([A-Z_]+[0-9]*)$/.test(error.message)?error.message:"WORKER_ERROR"); throw new Error("Notification worker failed; check configuration and IAM permissions"); }
  }
};
