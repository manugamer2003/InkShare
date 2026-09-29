import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, getDocs, collection, writeBatch, runTransaction, serverTimestamp, deleteDoc, onSnapshot, setLogLevel, query, where, updateDoc } from 'firebase/firestore';
setLogLevel('silent');

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-inkshare', firestore: {
    host: '127.0.0.1', port: 8085, rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8')
  }});
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
const google = uid => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'google.com' } }).firestore();
async function register(db, uid, username) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'users', uid), { uid, username, createdAt: serverTimestamp() });
  batch.set(doc(db, 'usernames', username), { uid });
  return batch.commit();
}
async function claim(db, uid, username) {
  return runTransaction(db, async tx => {
    const profile = doc(db, 'users', uid), name = doc(db, 'usernames', username);
    await tx.get(profile);
    if ((await tx.get(name)).exists()) throw new Error('occupied');
    tx.set(profile, { uid, username, createdAt: serverTimestamp() });
    tx.set(name, { uid });
  });
}
test('first registration creates matching profile and name; login can read profile', async () => {
  const db = google('alice');
  await assertSucceeds(register(db, 'alice', 'alice_1'));
  assert.equal((await getDoc(doc(db, 'users', 'alice'))).data().username, 'alice_1');
});
test('two concurrent users cannot claim the same name', async () => {
  const results = await Promise.allSettled([claim(google('alice'), 'alice', 'shared'), claim(google('bob'), 'bob', 'shared')]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
});
test('profile and reservation must be created atomically', async () => {
  const db = google('alice');
  await assertFails(setDoc(doc(db, 'users', 'alice'), { uid: 'alice', username: 'alice', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(db, 'usernames', 'alice'), { uid: 'alice' }));
});
test('invalid names, aliases and identity spoofing are rejected', async () => {
  const db = google('alice');
  await assertFails(register(db, 'alice', 'Alice'));
  await assertFails(register(db, 'alice', 'ab'));
  await assertFails(register(db, 'bob', 'bob'));
  await register(db, 'alice', 'alice');
  await assertFails(setDoc(doc(db, 'usernames', 'alias'), { uid: 'alice' }));
  await assertFails(setDoc(doc(db, 'users', 'alice'), { uid: 'alice', username: 'alias', createdAt: serverTimestamp() }));
  await assertFails(deleteDoc(doc(db, 'usernames', 'alice')));
});
test('anonymous and password sessions cannot register or search names', async () => {
  for (const db of [env.unauthenticatedContext().firestore(), env.authenticatedContext('alice', { firebase: { sign_in_provider: 'password' } }).firestore()]) {
    await assertFails(register(db, 'alice', 'alice'));
    await assertFails(getDoc(doc(db, 'usernames', 'alice')));
  }
});
test('exact lookup works but registry listing and foreign profile reads are denied', async () => {
  const a = google('alice'), b = google('bob');
  await register(a, 'alice', 'alice');
  await assertSucceeds(getDoc(doc(b, 'usernames', 'alice')));
  await assertFails(getDocs(collection(b, 'usernames')));
  await assertFails(getDoc(doc(b, 'users', 'alice')));
});
test('legacy friends remain readable and removable but direct additions are denied', async () => {
  const a = google('alice'), b = google('bob');
  await register(a, 'alice', 'alice'); await register(b, 'bob', 'bob');
  const ref = doc(a, 'users/alice/friends/bob');
  await assertFails(setDoc(ref, { uid: 'bob', username: 'bob', createdAt: serverTimestamp() }));
  await seedLegacy(ref, { uid: 'bob', username: 'bob', createdAt: serverTimestamp() });
  await assertSucceeds(getDocs(collection(a, 'users/alice/friends')));
  await assertFails(setDoc(ref, { uid: 'bob', username: 'bob', createdAt: serverTimestamp() }));
  await assertSucceeds(deleteDoc(ref));
});
test('self-add, fake names and writing another users friends are denied', async () => {
  const a = google('alice'), b = google('bob');
  await register(a, 'alice', 'alice'); await register(b, 'bob', 'bob');
  await assertFails(setDoc(doc(a, 'users/alice/friends/alice'), { uid: 'alice', username: 'alice', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(a, 'users/alice/friends/bob'), { uid: 'bob', username: 'fake_name', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(a, 'users/bob/friends/alice'), { uid: 'alice', username: 'alice', createdAt: serverTimestamp() }));
  await assertFails(getDocs(collection(a, 'users/bob/friends')));
});
test('collection backups remain private to their owner', async () => {
  const a = google('alice'), b = google('bob');
  await register(a, 'alice', 'alice'); await register(b, 'bob', 'bob');
  await assertSucceeds(setDoc(doc(a, 'users/alice/backups/collection'), { schemaVersion: 1, counts: { '1_normal': 2 } }));
  await assertSucceeds(getDoc(doc(a, 'users/alice/backups/collection')));
  await assertFails(getDoc(doc(b, 'users/alice/backups/collection')));
  await assertFails(setDoc(doc(b, 'users/alice/backups/collection'), { schemaVersion: 1, counts: {} }));
});

const cardData = (quantity, foil = false) => ({ cardId: 1, foil, quantity, updatedAt: serverTimestamp() });
test('collection persists by uid and normal/foil separately', async () => {
  const db = google('alice'); await register(db, 'alice', 'alice');
  await assertSucceeds(setDoc(doc(db, 'users/alice/collection/1_normal'), cardData(2)));
  await assertSucceeds(setDoc(doc(db, 'users/alice/collection/1_foil'), cardData(1, true)));
  const secondDevice = google('alice');
  assert.equal((await getDoc(doc(secondDevice, 'users/alice/collection/1_normal'))).data().quantity, 2);
  assert.equal((await getDocs(collection(secondDevice, 'users/alice/collection'))).size, 2);
});
test('simultaneous increments on two devices do not lose copies', async () => {
  const a = google('alice'), b = google('alice'); await register(a, 'alice', 'alice');
  const increase = db => runTransaction(db, async tx => {
    const ref = doc(db, 'users/alice/collection/1_normal');
    const old = await tx.get(ref);
    tx.set(ref, cardData((old.exists() ? old.data().quantity : 0) + 1));
  });
  await Promise.all([increase(a), increase(b)]);
  assert.equal((await getDoc(doc(a, 'users/alice/collection/1_normal'))).data().quantity, 2);
});
test('collection rejects foreign access, malformed entries and invalid quantities', async () => {
  const a = google('alice'), b = google('bob'); await register(a, 'alice', 'alice'); await register(b, 'bob', 'bob');
  const path = 'users/alice/collection/1_normal';
  await setDoc(doc(a, path), cardData(1));
  await assertFails(getDocs(collection(b, 'users/alice/collection')));
  await assertFails(setDoc(doc(b, path), cardData(2)));
  await assertFails(deleteDoc(doc(b, path)));
  for (const quantity of [-1, 0, 10000, 1.5, '1']) await assertFails(setDoc(doc(a, path), cardData(quantity)));
  await assertFails(setDoc(doc(a, 'users/alice/collection/2_normal'), cardData(1)));
  await assertFails(setDoc(doc(a, path), { ...cardData(1), uid: 'alice' }));
  await assertSucceeds(deleteDoc(doc(a, path)));
});
test('collection requires Google and a completed profile', async () => {
  await assertFails(setDoc(doc(google('alice'), 'users/alice/collection/1_normal'), cardData(1)));
  await register(google('alice'), 'alice', 'alice');
  const password = env.authenticatedContext('alice', { firebase: { sign_in_provider: 'password' } }).firestore();
  await assertFails(setDoc(doc(password, 'users/alice/collection/1_normal'), cardData(1)));
  await assertFails(getDocs(collection(password, 'users/alice/collection')));
});
test('collection listener receives changes from another device', async () => {
  const a = google('alice'), b = google('alice'); await register(a, 'alice', 'alice');
  let unsubscribe;
  let ready;
  const connected = new Promise(resolve => { ready = resolve; });
  const observed = new Promise((resolve, reject) => {
    const timer = setTimeout(() => { unsubscribe?.(); reject(new Error('Listener timed out')); }, 30000);
    unsubscribe = onSnapshot(doc(b, 'users/alice/collection/1_normal'), { includeMetadataChanges: true }, snapshot => {
      if (!snapshot.metadata.fromCache) ready();
      if (snapshot.exists() && snapshot.data().quantity === 3) { clearTimeout(timer); resolve(); }
    }, error => { clearTimeout(timer); reject(error); });
  });
  try { await Promise.race([connected, observed]); await setDoc(doc(a, 'users/alice/collection/1_normal'), cardData(3)); await observed; }
  finally { unsubscribe?.(); }
});
test('repeated imports preserve higher quantity instead of duplicating', async () => {
  const db = google('alice'); await register(db, 'alice', 'alice');
  const ref = doc(db, 'users/alice/collection/1_normal');
  const merge = quantity => runTransaction(db, async tx => {
    const previous = await tx.get(ref);
    if (!previous.exists() || previous.data().quantity < quantity) tx.set(ref, cardData(quantity));
  });
  await merge(3); await merge(3); await merge(1);
  assert.equal((await getDoc(ref)).data().quantity, 3);
});

async function loanSetup() {
  const a = google('alice'), b = google('bob'), c = google('charlie');
  await register(a, 'alice', 'alice'); await register(b, 'bob', 'bob'); await register(c, 'charlie', 'charlie');
  await seedLegacy(doc(a, 'users/alice/friends/bob'), { uid:'bob', username:'bob', createdAt:serverTimestamp() });
  return {a,b,c};
}
const loanData = () => ({lenderUid:'alice', lenderName:'alice', borrowerUid:'bob', borrowerName:'bob',
  participants:['alice','bob'], items:[{cardId:1,name:'Ariel',identifier:'1/204',foil:false,quantity:2}],
  status:'active',createdAt:serverTimestamp(),closedAt:null});
test('loan is visible to both participants through the app query, not outsiders', async () => {
  const {a,b,c} = await loanSetup();
  await assertSucceeds(setDoc(doc(a,'loans/one'),loanData()));
  for (const [db,uid] of [[a,'alice'],[b,'bob']]) {
    assert.equal((await assertSucceeds(getDocs(query(collection(db,'loans'),where('participants','array-contains',uid))))).size,1);
  }
  await assertFails(getDoc(doc(c,'loans/one')));
  await assertFails(getDocs(collection(b,'loans')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'loans/one')));
});
test('only creator can close; history survives friendship removal and cannot be altered', async () => {
  const {a,b} = await loanSetup(); await setDoc(doc(a,'loans/one'),loanData());
  await assertFails(updateDoc(doc(b,'loans/one'),{status:'closed',closedAt:serverTimestamp()}));
  await deleteDoc(doc(a,'users/alice/friends/bob'));
  await assertSucceeds(updateDoc(doc(a,'loans/one'),{status:'closed',closedAt:serverTimestamp()}));
  assert.equal((await getDoc(doc(b,'loans/one'))).data().status,'closed');
  await assertFails(updateDoc(doc(a,'loans/one'),{status:'active',closedAt:null}));
  await assertFails(deleteDoc(doc(a,'loans/one')));
});
test('loans reject spoofed identity, missing friendship, extra participants and malformed items', async () => {
  const {a,b} = await loanSetup();
  await assertFails(setDoc(doc(b,'loans/spoof'),loanData()));
  for (const patch of [{lenderName:'fake'}, {borrowerName:'charlie'}, {participants:['alice','bob','charlie']},
    {borrowerUid:'alice'}, {status:'closed'}, {items:[]}, {items:[{...loanData().items[0],quantity:0}]},
    {items:[{...loanData().items[0],quantity:1.5}]}, {items:[{...loanData().items[0],foil:'false'}]},
    {items:[...Array(9).fill(loanData().items[0]),{...loanData().items[0],quantity:-1}]},
    {items:Array(11).fill(loanData().items[0])}]) {
    await assertFails(setDoc(doc(a,'loans/invalid'),{...loanData(),...patch}));
  }
  await assertSucceeds(setDoc(doc(a,'loans/max'),{...loanData(),items:Array(10).fill(loanData().items[0])}));
  await deleteDoc(doc(a,'users/alice/friends/bob'));
  await assertFails(setDoc(doc(a,'loans/noFriend'),loanData()));
});
test('closing cannot change cards or participants and is seen by the borrower listener', async () => {
  const {a,b} = await loanSetup(); await setDoc(doc(a,'loans/one'),loanData());
  await assertFails(updateDoc(doc(a,'loans/one'),{status:'closed',closedAt:serverTimestamp(),items:[]}));
  let unsubscribe;
  const observed = new Promise((resolve,reject) => {
    const timer = setTimeout(()=>{unsubscribe?.();reject(new Error('Loan listener timed out'));},30000);
    unsubscribe=onSnapshot(doc(b,'loans/one'), snapshot=>{
      if(snapshot.data()?.status==='closed'){clearTimeout(timer);resolve();}
    },error=>{clearTimeout(timer);reject(error);});
  });
  try { await updateDoc(doc(a,'loans/one'),{status:'closed',closedAt:serverTimestamp()}); await observed; }
  finally {unsubscribe?.();}
});

test('loan creation transaction can read a new id and retry without duplication', async () => {
  const {a} = await loanSetup();
  const create = () => runTransaction(a, async tx => {
    const ref = doc(a,'loans/retry');
    const previous = await tx.get(ref);
    if (!previous.exists()) tx.set(ref,loanData());
  });
  await assertSucceeds(create()); await assertSucceeds(create());
  assert.equal((await getDocs(query(collection(a,'loans'),where('participants','array-contains','alice')))).size,1);
});

test('mutual friends can read collections but never write or read private profile data', async () => {
  const {a,b,c}=await loanSetup();
  await setDoc(doc(a,'users/alice/collection/1_normal'),cardData(3));
  await assertFails(getDocs(collection(b,'users/alice/collection')));
  await seedLegacy(doc(b,'users/bob/friends/alice'),{uid:'alice',username:'alice',createdAt:serverTimestamp()});
  assert.equal((await assertSucceeds(getDocs(collection(b,'users/alice/collection')))).size,1);
  await assertSucceeds(getDoc(doc(b,'users/alice/collection/1_normal')));
  await assertSucceeds(getDocs(collection(a,'users/bob/collection')));
  await assertFails(setDoc(doc(b,'users/alice/collection/1_normal'),cardData(4)));
  await assertFails(deleteDoc(doc(b,'users/alice/collection/1_normal')));
  await assertFails(getDoc(doc(b,'users/alice')));
  await assertFails(getDocs(collection(b,'users/alice/friends')));
  await assertFails(getDoc(doc(b,'users/alice/backups/collection')));
  await assertFails(getDocs(collection(c,'users/alice/collection')));
});
test('removing either friendship revokes collection access', async () => {
  const {a,b}=await loanSetup();
  const reciprocal=doc(b,'users/bob/friends/alice');
  const forward=doc(a,'users/alice/friends/bob');
  await seedLegacy(reciprocal,{uid:'alice',username:'alice',createdAt:serverTimestamp()});
  await assertSucceeds(getDocs(collection(b,'users/alice/collection')));
  await deleteDoc(forward);
  await assertFails(getDocs(collection(b,'users/alice/collection')));
  await assertFails(getDocs(collection(a,'users/bob/collection')));
  await seedLegacy(forward,{uid:'bob',username:'bob',createdAt:serverTimestamp()});
  await deleteDoc(reciprocal);
  await assertFails(getDocs(collection(b,'users/alice/collection')));
  await assertFails(getDocs(collection(a,'users/bob/collection')));
});

// Fixtures created before request-based friendship existed; not a client write bypass.
async function seedLegacy(ref, data) {
  await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(),ref.path),data));
}
const invitation = () => ({senderUid:'alice',receiverUid:'bob',senderName:'alice',receiverName:'bob',createdAt:serverTimestamp()});
async function requestSetup() {
  const a=google('alice'),b=google('bob'),c=google('charlie');
  await register(a,'alice','alice'); await register(b,'bob','bob'); await register(c,'charlie','charlie');
  return {a,b,c};
}
function acceptInvitation(db, {oneSided=false,consume=true}={}) {
  const batch=writeBatch(db);
  batch.set(doc(db,'users/bob/friends/alice'),{uid:'alice',username:'alice',createdAt:serverTimestamp()});
  if(!oneSided) batch.set(doc(db,'users/alice/friends/bob'),{uid:'bob',username:'bob',createdAt:serverTimestamp()});
  if(consume) batch.delete(doc(db,'friendRequests/alice:bob'));
  return batch.commit();
}
test('request is private, pending does not grant collection access, receiver accepts atomically',async()=>{
  const {a,b,c}=await requestSetup();
  await assertSucceeds(setDoc(doc(a,'friendRequests/alice:bob'),invitation()));
  assert.equal((await getDocs(query(collection(b,'friendRequests'),where('receiverUid','==','bob')))).size,1);
  assert.equal((await getDocs(query(collection(a,'friendRequests'),where('senderUid','==','alice')))).size,1);
  await assertFails(getDocs(collection(a,'friendRequests')));
  await assertFails(getDoc(doc(c,'friendRequests/alice:bob')));
  await assertFails(getDocs(collection(a,'users/bob/collection')));
  await assertSucceeds(acceptInvitation(b));
  assert.equal((await getDocs(collection(a,'users/alice/friends'))).size,1);
  assert.equal((await getDocs(collection(b,'users/bob/friends'))).size,1);
  assert.equal((await getDoc(doc(a,'friendRequests/alice:bob'))).exists(),false);
  await assertSucceeds(getDocs(collection(a,'users/bob/collection')));
  await assertSucceeds(getDocs(collection(b,'users/alice/collection')));
  await assertFails(setDoc(doc(a,'friendRequests/alice:bob'),invitation()));
});
test('sender and stranger cannot accept and partial acceptance is denied',async()=>{
  const {a,b,c}=await requestSetup();
  await setDoc(doc(a,'friendRequests/alice:bob'),invitation());
  await assertFails(acceptInvitation(a)); await assertFails(acceptInvitation(c));
  await assertFails(acceptInvitation(b,{oneSided:true}));
  await assertFails(acceptInvitation(b,{consume:false}));
  assert.equal((await getDoc(doc(b,'friendRequests/alice:bob'))).exists(),true);
});
test('duplicate, reverse, self and forged requests are rejected',async()=>{
  const {a,b,c}=await requestSetup();
  await assertFails(setDoc(doc(c,'friendRequests/alice:bob'),invitation()));
  await assertFails(setDoc(doc(a,'friendRequests/alice:alice'),{...invitation(),receiverUid:'alice',receiverName:'alice'}));
  await assertFails(setDoc(doc(a,'friendRequests/alice:bob'),{...invitation(),receiverUid:'charlie'}));
  await setDoc(doc(a,'friendRequests/alice:bob'),invitation());
  await assertFails(setDoc(doc(a,'friendRequests/alice:bob'),invitation()));
  await assertFails(setDoc(doc(b,'friendRequests/bob:alice'),{senderUid:'bob',receiverUid:'alice',senderName:'bob',receiverName:'alice',createdAt:serverTimestamp()}));
});
test('receiver rejects, sender cancels, neither grants friendship, request cannot be accepted afterwards',async()=>{
  const {a,b,c}=await requestSetup(); const ref=doc(a,'friendRequests/alice:bob');
  await setDoc(ref,invitation());
  await assertFails(deleteDoc(doc(c,'friendRequests/alice:bob')));
  await assertSucceeds(deleteDoc(doc(b,'friendRequests/alice:bob')));
  await assertFails(acceptInvitation(b));
  await setDoc(ref,invitation()); await assertSucceeds(deleteDoc(ref));
  await assertFails(acceptInvitation(b));
  assert.equal((await getDocs(collection(a,'users/alice/friends'))).size,0);
});
test('acceptance upgrades a one-sided legacy contact, removing friendship clears both lists',async()=>{
  const {a,b,c}=await requestSetup();
  await seedLegacy(doc(a,'users/alice/friends/bob'),{uid:'bob',username:'bob',createdAt:serverTimestamp()});
  await setDoc(doc(a,'friendRequests/alice:bob'),invitation());
  await assertSucceeds(acceptInvitation(b));
  await assertFails(deleteDoc(doc(c,'users/bob/friends/alice')));
  await assertFails(deleteDoc(doc(a,'users/bob/friends/alice')));
  const batch=writeBatch(a); batch.delete(doc(a,'users/alice/friends/bob')); batch.delete(doc(a,'users/bob/friends/alice'));
  await assertSucceeds(batch.commit());
  assert.equal((await getDocs(collection(b,'users/bob/friends'))).size,0);
  await assertFails(getDocs(collection(a,'users/bob/collection')));
  await assertFails(acceptInvitation(b));
});

async function saveScanBatch(db, uid, id, values) {
  return runTransaction(db, async tx => {
    const receipt=doc(db,`users/${uid}/scanBatches/${id}`);
    if((await tx.get(receipt)).exists()) return;
    const rows=[];
    for (const [cardId, quantity] of values) {
      const ref=doc(db,`users/${uid}/collection/${cardId}_normal`);
      const current=await tx.get(ref);
      rows.push([ref,{cardId,foil:false,quantity:(current.data()?.quantity ?? 0)+quantity,updatedAt:serverTimestamp()}]);
    }
    rows.forEach(([ref,data])=>tx.set(ref,data));
    tx.set(receipt,{createdAt:serverTimestamp()});
  });
}
test('scan batch adds quantities and retry does not duplicate; receipts are private and immutable',async()=>{
  const {a,b}=await requestSetup();
  await assertSucceeds(saveScanBatch(a,'alice','batch1',[[1,2],[2,3]]));
  await assertSucceeds(saveScanBatch(a,'alice','batch1',[[1,2],[2,3]]));
  assert.equal((await getDoc(doc(a,'users/alice/collection/1_normal'))).data().quantity,2);
  assert.equal((await getDoc(doc(a,'users/alice/collection/2_normal'))).data().quantity,3);
  await assertSucceeds(saveScanBatch(a,'alice','batch2',[[1,1]]));
  assert.equal((await getDoc(doc(a,'users/alice/collection/1_normal'))).data().quantity,3);
  await assertFails(getDoc(doc(b,'users/alice/scanBatches/batch1')));
  await assertFails(deleteDoc(doc(a,'users/alice/scanBatches/batch1')));
  await assertFails(setDoc(doc(a,'users/alice/scanBatches/batch1'),{createdAt:serverTimestamp()}));
  await assertFails(saveScanBatch(b,'alice','foreign',[[1,1]]));
});
test('invalid scan batch rolls back all cards and receipt',async()=>{
  const {a}=await requestSetup();
  await assertFails(saveScanBatch(a,'alice','overflow',[[1,2],[2,10000]]));
  assert.equal((await getDoc(doc(a,'users/alice/collection/1_normal'))).exists(),false);
  assert.equal((await getDoc(doc(a,'users/alice/scanBatches/overflow'))).exists(),false);
});
test('scan batch supports 100 distinct entries in one transaction',async()=>{
  const {a}=await requestSetup();
  await assertSucceeds(saveScanBatch(a,'alice','large',Array.from({length:100},(_,i)=>[i+1,1])));
  assert.equal((await getDocs(collection(a,'users/alice/collection'))).size,100);
});

const loanRequestData = () => {
  const {closedAt,...loan}=loanData();
  return {...loan,status:'pending',resolvedAt:null};
};
async function loanRequestSetup() {
  const users=await loanSetup();
  await seedLegacy(doc(users.b,'users/bob/friends/alice'),{uid:'alice',username:'alice',createdAt:serverTimestamp()});
  return users;
}
function acceptLoanRequest(db, id='request1', loan=loanData()) {
  const batch=writeBatch(db);
  batch.set(doc(db,`loans/${id}`),loan);
  batch.delete(doc(db,`loanRequests/${id}`));
  return batch.commit();
}
test('loan requests are private and only borrower can send a valid mutual-friend request',async()=>{
  const {a,b,c}=await loanRequestSetup();
  await assertFails(setDoc(doc(a,'loanRequests/wrong'),loanRequestData()));
  await assertFails(setDoc(doc(c,'loanRequests/wrong'),loanRequestData()));
  await assertSucceeds(setDoc(doc(b,'loanRequests/request1'),loanRequestData()));
  for (const [db,uid] of [[a,'alice'],[b,'bob']]) {
    assert.equal((await getDocs(query(collection(db,'loanRequests'),where('participants','array-contains',uid)))).size,1);
  }
  await assertFails(getDoc(doc(c,'loanRequests/request1')));
  await assertFails(getDocs(collection(a,'loanRequests')));
  for (const change of [{borrowerName:'fake'},{lenderName:'fake'},{participants:['alice','bob','charlie']},{status:'accepted'},{items:[]},{items:[{...loanData().items[0],quantity:0}]}]) {
    await assertFails(setDoc(doc(b,'loanRequests/invalid'),{...loanRequestData(),...change}));
  }
  await deleteDoc(doc(a,'users/alice/friends/bob'));
  await assertFails(setDoc(doc(b,'loanRequests/unfriend'),loanRequestData()));
});
test('only lender accepts with exact loan created atomically; replay and alteration are denied',async()=>{
  const {a,b,c}=await loanRequestSetup();
  await setDoc(doc(b,'loanRequests/request1'),loanRequestData());
  await assertFails(acceptLoanRequest(b)); await assertFails(acceptLoanRequest(c));
  await assertFails(updateDoc(doc(a,'loanRequests/request1'),{status:'accepted',resolvedAt:serverTimestamp()}));
  await assertFails(acceptLoanRequest(a,'request1',{...loanData(),items:[{...loanData().items[0],quantity:1}]}));
  await assertSucceeds(acceptLoanRequest(a));
  assert.equal((await getDoc(doc(b,'loans/request1'))).data().status,'active');
  assert.equal((await getDoc(doc(b,'loanRequests/request1'))).exists(),false);
  await assertFails(acceptLoanRequest(a));
  await assertFails(updateDoc(doc(a,'loanRequests/request1'),{status:'pending',resolvedAt:null}));
  await assertFails(deleteDoc(doc(b,'loanRequests/request1')));
});
test('borrower cancels, lender rejects, resolved requests cannot become loans',async()=>{
  const {a,b,c}=await loanRequestSetup();
  for (const [id,action,db] of [['cancel','cancelled',b],['reject','rejected',a]]) {
    await setDoc(doc(b,`loanRequests/${id}`),loanRequestData());
    await assertFails(updateDoc(doc(c,`loanRequests/${id}`),{status:action,resolvedAt:serverTimestamp()}));
    await assertFails(updateDoc(doc(action==='cancelled'?a:b,`loanRequests/${id}`),{status:action,resolvedAt:serverTimestamp()}));
    if(action==='rejected') {
      await assertFails(deleteDoc(doc(b,`loanRequests/${id}`)));
      await assertFails(deleteDoc(doc(c,`loanRequests/${id}`)));
      await assertSucceeds(deleteDoc(doc(db,`loanRequests/${id}`)));
      assert.equal((await getDoc(doc(b,`loanRequests/${id}`))).exists(),false);
    } else await assertSucceeds(updateDoc(doc(db,`loanRequests/${id}`),{status:action,resolvedAt:serverTimestamp()}));
    await assertFails(acceptLoanRequest(a,id));
    assert.equal((await getDoc(doc(a,`loans/${id}`))).exists(),false);
  }
});
test('request items cannot be edited, acceptance requires friendship, rejection remains possible',async()=>{
  const {a,b}=await loanRequestSetup();
  await setDoc(doc(b,'loanRequests/request1'),loanRequestData());
  await assertFails(updateDoc(doc(b,'loanRequests/request1'),{items:[]}));
  await deleteDoc(doc(b,'users/bob/friends/alice'));
  await assertFails(acceptLoanRequest(a));
  await assertSucceeds(deleteDoc(doc(a,'loanRequests/request1')));
});
test('maximum ten-item loan request can be accepted atomically',async()=>{
  const {a,b}=await loanRequestSetup();
  const items=Array.from({length:10},(_,i)=>({...loanData().items[0],cardId:i+1}));
  await assertSucceeds(setDoc(doc(b,'loanRequests/request1'),{...loanRequestData(),items}));
  await assertSucceeds(acceptLoanRequest(a,'request1',{...loanData(),items}));
});

const returnEdit = (remaining,revision=1) => ({remaining,revision,updatedAt:serverTimestamp(),
  status:remaining.every(n=>n===0)?'closed':'active',closedAt:remaining.every(n=>n===0)?serverTimestamp():null});
test('lender partially returns legacy loan, corrects pending counts, then closes; borrower sees balance',async()=>{
  const {a,b,c}=await loanSetup();
  await setDoc(doc(a,'loans/partial'),loanData());
  await assertFails(updateDoc(doc(b,'loans/partial'),returnEdit([1])));
  await assertFails(updateDoc(doc(c,'loans/partial'),returnEdit([1])));
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit([1])));
  const partial=(await getDoc(doc(b,'loans/partial'))).data();
  assert.deepEqual(partial.remaining,[1]); assert.equal(partial.items[0].quantity,2); assert.equal(partial.status,'active');
  await assertFails(updateDoc(doc(a,'loans/partial'),returnEdit([2],1)));
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit([2],2)));
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit([0],3)));
  assert.equal((await getDoc(doc(b,'loans/partial'))).data().status,'closed');
  await assertFails(updateDoc(doc(a,'loans/partial'),returnEdit([1],4)));
});
test('partial returns reject invalid counts, inconsistent closure and changes to original cards',async()=>{
  const {a}=await loanSetup(); await setDoc(doc(a,'loans/partial'),loanData());
  for (const counts of [[],[1,1],[-1],[3],[0.5],['1']]) {
    await assertFails(updateDoc(doc(a,'loans/partial'),returnEdit(counts)));
  }
  await assertFails(updateDoc(doc(a,'loans/partial'),{...returnEdit([0]),status:'active',closedAt:null}));
  await assertFails(updateDoc(doc(a,'loans/partial'),{...returnEdit([1]),status:'closed',closedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(a,'loans/partial'),{...returnEdit([1]),items:[{...loanData().items[0],quantity:3}]}));
  await assertFails(updateDoc(doc(a,'loans/partial'),{...returnEdit([1]),borrowerUid:'charlie'}));
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit([1])));
  await assertFails(updateDoc(doc(a,'loans/partial'),{status:'closed',closedAt:serverTimestamp()}));
});
test('ten-row partial returns and final closure remain within rules limits after unfriend',async()=>{
  const {a,b}=await loanSetup();
  const items=Array.from({length:10},(_,i)=>({...loanData().items[0],cardId:i+1}));
  await setDoc(doc(a,'loans/partial'),{...loanData(),items});
  await deleteDoc(doc(a,'users/alice/friends/bob'));
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit(Array(10).fill(1))));
  assert.equal((await getDoc(doc(b,'loans/partial'))).data().remaining.length,10);
  await assertSucceeds(updateDoc(doc(a,'loans/partial'),returnEdit(Array(10).fill(0),2)));
});

const notice = (kind,outcome,actorUid,recipientUid,requestId) => ({kind,outcome,actorUid,actorName:actorUid,recipientUid,requestId,state:'pending',createdAt:serverTimestamp()});
test('device tokens are private and owner-only with validated timestamps',async()=>{
 const {a,b}=await requestSetup();const path='users/alice/devices/'+ 'a'.repeat(64);
 const data={token:'a-token-longer-than-twenty-characters',updatedAt:serverTimestamp()};
 await assertSucceeds(setDoc(doc(a,path),data)); await assertSucceeds(getDoc(doc(a,path)));
 await assertFails(getDoc(doc(b,path)));await assertFails(setDoc(doc(b,path),data));
 await assertFails(setDoc(doc(a,path),{...data,token:'bad'}));await assertFails(getDocs(collection(a,'users/alice/devices')));
 await assertSucceeds(deleteDoc(doc(a,path)));
});
test('friend create and accepted events are atomic, private and cannot be forged or replayed',async()=>{
 const {a,b,c}=await requestSetup();
 const event=notice('friend','requested','alice','bob','alice:bob');
 await assertFails(setDoc(doc(a,'notificationOutbox/friend1'),event));
 const send=writeBatch(a);send.set(doc(a,'friendRequests/alice:bob'),{...invitation(),notificationId:'friend1'});send.set(doc(a,'notificationOutbox/friend1'),event);await assertSucceeds(send.commit());
 await assertFails(getDoc(doc(a,'notificationOutbox/friend1')));
 await assertFails(setDoc(doc(c,'notificationOutbox/fake'),event));
 const accept=writeBatch(b);
 accept.set(doc(b,'users/bob/friends/alice'),{uid:'alice',username:'alice',createdAt:serverTimestamp()});
 accept.set(doc(b,'users/alice/friends/bob'),{uid:'bob',username:'bob',createdAt:serverTimestamp()});
 accept.delete(doc(b,'friendRequests/alice:bob'));
 accept.set(doc(b,'notificationOutbox/friend1_result'),notice('friend','accepted','bob','alice','alice:bob'));
 await assertSucceeds(accept.commit());
 await assertFails(setDoc(doc(b,'notificationOutbox/friend1_result'),notice('friend','accepted','bob','alice','alice:bob')));
});
test('friend rejection event is receiver-only and cannot claim acceptance',async()=>{
 const {a,b}=await requestSetup();await setDoc(doc(a,'friendRequests/alice:bob'),{...invitation(),notificationId:'friend2'});
 const bad=writeBatch(b);bad.delete(doc(b,'friendRequests/alice:bob'));bad.set(doc(b,'notificationOutbox/friend2_result'),notice('friend','accepted','bob','alice','alice:bob'));await assertFails(bad.commit());
 const reject=writeBatch(b);reject.delete(doc(b,'friendRequests/alice:bob'));reject.set(doc(b,'notificationOutbox/friend2_result'),notice('friend','rejected','bob','alice','alice:bob'));await assertSucceeds(reject.commit());
});
test('loan request and accepted notification are atomic even for ten cards',async()=>{
 const {a,b}=await loanRequestSetup();
 const items=Array.from({length:10},(_,i)=>({...loanData().items[0],cardId:i+1}));
 const send=writeBatch(b);send.set(doc(b,'loanRequests/pushloan'),{...loanRequestData(),items,notificationId:'pushloan'});send.set(doc(b,'notificationOutbox/pushloan'),notice('loan','requested','bob','alice','pushloan'));await assertSucceeds(send.commit());
 const accept=writeBatch(a);accept.set(doc(a,'loans/pushloan'),{...loanData(),items});accept.delete(doc(a,'loanRequests/pushloan'));accept.set(doc(a,'notificationOutbox/pushloan_result'),notice('loan','accepted','alice','bob','pushloan'));await assertSucceeds(accept.commit());
});
test('loan rejection sends only valid receiver event and cancellation cannot forge rejection',async()=>{
 const {a,b}=await loanRequestSetup();await setDoc(doc(b,'loanRequests/rejectpush'),{...loanRequestData(),notificationId:'rejectpush'});
 await assertFails(setDoc(doc(b,'notificationOutbox/rejectpush_result'),notice('loan','rejected','alice','bob','rejectpush')));
 const reject=writeBatch(a);reject.delete(doc(a,'loanRequests/rejectpush'));reject.set(doc(a,'notificationOutbox/rejectpush_result'),notice('loan','rejected','alice','bob','rejectpush'));await assertSucceeds(reject.commit());
});

test('wishlist is private, owner writable, and only mutual friends can view', async () => {
  const {a,b,c}=await requestSetup();
  const ref=doc(a,'users/alice/wishlist/1574');
  await assertSucceeds(setDoc(ref,{cardId:1574,updatedAt:serverTimestamp()}));
  await assertSucceeds(getDocs(collection(a,'users/alice/wishlist')));
  await assertFails(getDocs(collection(b,'users/alice/wishlist')));
  await seedLegacy(doc(a,'users/alice/friends/bob'),{uid:'bob',username:'bob',createdAt:serverTimestamp()});
  await assertFails(getDocs(collection(b,'users/alice/wishlist')));
  await seedLegacy(doc(b,'users/bob/friends/alice'),{uid:'alice',username:'alice',createdAt:serverTimestamp()});
  await assertSucceeds(getDocs(collection(b,'users/alice/wishlist')));
  await assertFails(getDocs(collection(c,'users/alice/wishlist')));
  await assertFails(setDoc(doc(b,'users/alice/wishlist/1574'),{cardId:1574,updatedAt:serverTimestamp()}));
  await assertFails(deleteDoc(doc(b,'users/alice/wishlist/1574')));
  await assertSucceeds(deleteDoc(doc(a,'users/alice/friends/bob')));
  await assertFails(getDocs(collection(b,'users/alice/wishlist')));
  await assertSucceeds(deleteDoc(ref));
});
test('wishlist rejects invalid IDs, spoofed fields and unauthenticated access', async () => {
  const {a}=await requestSetup();
  for (const data of [{cardId:0,updatedAt:serverTimestamp()},{cardId:2,updatedAt:serverTimestamp()},{cardId:1,updatedAt:serverTimestamp(),uid:'bob'},{cardId:1}]) {
    await assertFails(setDoc(doc(a,'users/alice/wishlist/1'),data));
  }
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(),'users/alice/wishlist')));
});
