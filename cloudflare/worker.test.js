import {test} from 'node:test';
import assert from 'node:assert/strict';
import worker,{messageFor,run} from './worker.js';
const stringValue=v=>({stringValue:v});
const doc=(extra={})=>({name:'projects/inkshare-7527e/databases/(default)/documents/notificationOutbox/event1',updateTime:'2026-09-18T10:00:00Z',fields:Object.fromEntries(Object.entries({kind:'friend',outcome:'accepted',actorName:'alice',recipientUid:'bob',requestId:'alice:bob',state:'pending',...extra}).map(([k,v])=>[k,stringValue(v)]))});
test('all six events have correct destination and no action buttons',()=>{
 for(const kind of ['friend','loan'])for(const outcome of ['requested','accepted','rejected']){
  const data=messageFor(doc({kind,outcome}),'event1');
  assert.equal(data.destination,kind==='friend'?'friends':'loans');
  assert.equal(data.tab,String(outcome==='requested'?(kind==='friend'?1:2):outcome==='accepted'?0:(kind==='friend'?2:3)));
  assert.match(data.body,/@alice/);assert.equal(data.recipientUid,'bob');assert.equal(data.eventId,'event1');assert.equal(data.actions,undefined);
 }
 assert.throws(()=>messageFor(doc({kind:'arbitrary'}),'x'));
 assert.throws(()=>messageFor(doc({outcome:'cancelled'}),'x'));
});
test('public endpoint never sends and rejects POST',async()=>{
 assert.equal((await worker.fetch(new Request('https://worker/'))).status,200);
 assert.equal((await worker.fetch(new Request('https://worker/',{method:'POST',body:'{}'}))).status,405);
});
test('scheduled sender handles delivery, invalid tokens, retry leases and stale requests',async()=>{
 const key=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
 const raw=await crypto.subtle.exportKey('pkcs8',key.privateKey);
 const env={FIREBASE_SERVICE_ACCOUNT:JSON.stringify({project_id:'inkshare-7527e',client_email:'test@example.com',private_key:'-----BEGIN PRIVATE KEY-----\n'+Buffer.from(raw).toString('base64')+'\n-----END PRIVATE KEY-----'})};
 const saved=globalThis.fetch;
 let scenario='success',calls=[],patches=[],outgoing=[];
 globalThis.fetch=async(url,opts={})=>{
  const u=String(url);calls.push(u);
  if(u.includes('oauth2.googleapis.com'))return Response.json({access_token:'fake-token',expires_in:3600});
  if(u.endsWith(':runQuery')){
   const event=doc({outcome:scenario==='obsolete'?'requested':'accepted'});
   event.fields.createdAt={timestampValue:new Date().toISOString()};
   if(scenario==='leased')event.fields.leaseUntil={integerValue:String(Date.now()+999999)};
   return Response.json([{document:event}]);
  }
  if(opts.method==='PATCH'){
   const fields=JSON.parse(opts.body).fields;patches.push(fields);
   const event=doc();event.fields.createdAt={timestampValue:new Date().toISOString()};
   event.fields.outcome=stringValue(scenario==='obsolete'?'requested':'accepted');
   return Response.json({...event,fields:{...event.fields,...fields}});
  }
  if(u.includes('/friendRequests/'))return new Response('',{status:404});
  if(u.includes('/devices?') && scenario==='no_devices')return Response.json({});
  if(u.includes('/devices?'))return Response.json({documents:[{name:'projects/inkshare-7527e/databases/(default)/documents/users/bob/devices/device',updateTime:'2026-09-18T10:00:00Z',fields:{token:stringValue('test-registration-token')}}]});
  if(u.includes('fcm.googleapis.com')){
   outgoing.push(JSON.parse(opts.body));
   if(scenario==='invalid')return Response.json({error:{details:[{errorCode:'UNREGISTERED'}]}},{status:404});
   if(scenario==='failure')return Response.json({error:{}},{status:503});
   return Response.json({name:'sent'});
  }
  if(opts.method==='DELETE')return Response.json({});
  throw new Error('Unexpected request');
 };
 try {
  await run(env);assert.equal(outgoing.length,1);assert.equal(outgoing[0].message.notification,undefined);assert.equal(outgoing[0].message.android.priority,'HIGH');assert.equal(patches.at(-1).state.stringValue,'sent');
  scenario='invalid';calls=[];patches=[];outgoing=[];await run(env);assert(calls.some(u=>u.includes('currentDocument.updateTime')&&u.includes('/devices/')));assert.equal(patches.at(-1).state.stringValue,'sent');
  scenario='failure';patches=[];await run(env);assert.equal(patches.at(-1).state.stringValue,'pending');assert.equal(patches.at(-1).attempts.integerValue,'1');
  scenario='no_devices';patches=[];outgoing=[];await run(env);assert.equal(outgoing.length,0);assert.equal(patches.at(-1).state.stringValue,'pending');assert.equal(patches.at(-1).lastError.stringValue,'NO_DEVICES');assert(Number(patches.at(-1).retryAfter.integerValue)>Date.now());
  scenario='success';patches=[];outgoing=[];await run(env);assert.equal(outgoing.length,1);assert.equal(patches.at(-1).state.stringValue,'sent');
  scenario='leased';patches=[];outgoing=[];await run(env);assert.equal(outgoing.length,0);assert.equal(patches.length,0);
  scenario='obsolete';patches=[];outgoing=[];await run(env);assert.equal(outgoing.length,0);assert.equal(patches.at(-1).state.stringValue,'obsolete');
 }finally{globalThis.fetch=saved;}
});
