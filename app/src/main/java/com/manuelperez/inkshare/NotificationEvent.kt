package com.manuelperez.inkshare
import com.google.firebase.firestore.FieldValue
fun notificationEvent(kind: String, requestId: String, outcome: String, actorUid: String,
    actorName: String, recipientUid: String) = mapOf("kind" to kind, "requestId" to requestId,
    "outcome" to outcome, "actorUid" to actorUid, "actorName" to actorName,
    "recipientUid" to recipientUid, "state" to "pending", "createdAt" to FieldValue.serverTimestamp())
