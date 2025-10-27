# WebRTC Signal Routing Architecture

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         SafeRoom Client                         │
│                                                                 │
│  ┌──────────────────┐              ┌──────────────────────┐   │
│  │  MessagesController│              │   CallManager        │   │
│  │  (Chat UI)        │              │   (Voice/Video)      │   │
│  └────────┬──────────┘              └──────────┬───────────┘   │
│           │                                    │               │
│           │ 1. User opens chat                 │ User makes call│
│           │ tryP2PConnection()                 │ makeCall()    │
│           │                                    │               │
│           ▼                                    ▼               │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │        P2PConnectionManager                              │ │
│  │        (WebRTC DataChannel for Messaging)                │ │
│  │                                                           │ │
│  │  2. createConnection()                                   │ │
│  │     → Create PeerConnection                              │ │
│  │     → Create DataChannel                                 │ │
│  │     → Create Offer                                       │ │
│  │     → Send P2P_OFFER with "p2p-" callId                  │ │
│  │                                                           │ │
│  │  Shares signalingClient from CallManager ───────────┐   │ │
│  └──────────────────────────────────────────────────────│───┘ │
│                                                         │     │
│  ┌──────────────────────────────────────────────────────│───┐ │
│  │        CallManager                                   │   │ │
│  │        (WebRTC for Voice/Video Calls)                │   │ │
│  │                                                       │   │ │
│  │  3. Owns WebRTCSignalingClient ◄─────────────────────┘   │ │
│  │     (Single instance shared)                             │ │
│  │                                                           │ │
│  │  4. handleIncomingSignal() - SIGNAL ROUTING:             │ │
│  │     ┌───────────────────────────────────────────┐        │ │
│  │     │ if (P2P_OFFER || P2P_ANSWER ||            │        │ │
│  │     │     ICE_CANDIDATE with "p2p-" callId)     │        │ │
│  │     │    → Route to P2PConnectionManager        │        │ │
│  │     │ else                                       │        │ │
│  │     │    → Handle in CallManager (voice/video)  │        │ │
│  │     └───────────────────────────────────────────┘        │ │
│  └──────────────────────────────┬────────────────────────────┘ │
│                                 │                              │
│                                 │ 5. Send/Receive signals      │
│                                 ▼                              │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │        WebRTCSignalingClient                            │  │
│  │        (Shared by both CallManager & P2PConnectionMgr)  │  │
│  │                                                          │  │
│  │  - sendSignalViaStream()                                │  │
│  │  - Callback: onSignalReceived()                         │  │
│  │    → Always goes to CallManager.handleIncomingSignal()  │  │
│  └─────────────────────────┬────────────────────────────────┘  │
│                            │                                   │
└────────────────────────────┼───────────────────────────────────┘
                             │
                             │ 6. Network (STUN/TURN/Direct)
                             │
                        ┌────▼─────┐
                        │  Server  │
                        │ (Signaling)│
                        └──────────┘
```

## Signal Flow: P2P Messaging (DataChannel)

### Offer Side (User A initiating connection)

```
User A                          Server                      User B
  │                              │                            │
  │ 1. Opens chat with User B    │                            │
  │    MessagesController        │                            │
  │    .tryP2PConnection("B")    │                            │
  │                              │                            │
  │ 2. P2PConnectionManager      │                            │
  │    .createConnection("B")    │                            │
  │    - Create PeerConnection   │                            │
  │    - Create DataChannel      │                            │
  │    - Create Offer            │                            │
  │                              │                            │
  │ 3. P2P_OFFER                 │                            │
  │    callId: "p2p-B-123456"    │                            │
  ├──────────────────────────────►                            │
  │                              │                            │
  │                              │ 4. Route to User B         │
  │                              ├────────────────────────────►
  │                              │                            │
  │                              │   P2P_OFFER received       │
  │                              │   CallManager routes to    │
  │                              │   P2PConnectionManager     │
  │                              │   (based on signal type)   │
  │                              │                            │
  │                              │   - Set remote description │
  │                              │   - Create answer          │
  │                              │                            │
  │                              │ 5. P2P_ANSWER              │
  │                              │    callId: "p2p-B-123456"  │
  │                              ◄────────────────────────────┤
  │ 6. P2P_ANSWER received       │                            │
  ◄──────────────────────────────┤                            │
  │                              │                            │
  │ CallManager routes to        │                            │
  │ P2PConnectionManager         │                            │
  │ - Set remote description     │                            │
  │                              │                            │
  │ 7. ICE_CANDIDATE             │                            │
  │    callId: "p2p-B-123456"    │                            │
  ├──────────────────────────────►                            │
  │                              ├────────────────────────────►
  │                              │                            │
  │                              │ 8. ICE_CANDIDATE           │
  │                              │    callId: "p2p-B-123456"  │
  │                              ◄────────────────────────────┤
  ◄──────────────────────────────┤                            │
  │                              │                            │
  │ 9. ICE Connected             │                            │
  │    DataChannel OPEN          │         DataChannel OPEN   │
  │                              │                            │
  │ 10. Send message via DataChannel                          │
  ├───────────────────────────────────────────────────────────►
  │                              │                            │
```

## Signal Flow: Voice/Video Call (Normal WebRTC)

### Caller (User A calling User B)

```
User A                          Server                      User B
  │                              │                            │
  │ 1. Clicks call button        │                            │
  │    CallManager.makeCall("B") │                            │
  │                              │                            │
  │ 2. Create PeerConnection     │                            │
  │    Add audio/video tracks    │                            │
  │    Create Offer              │                            │
  │                              │                            │
  │ 3. OFFER                     │                            │
  │    callId: "call-123456"     │                            │
  │    (NO "p2p-" prefix)        │                            │
  ├──────────────────────────────►                            │
  │                              │                            │
  │                              │ 4. Route to User B         │
  │                              ├────────────────────────────►
  │                              │                            │
  │                              │   OFFER received           │
  │                              │   CallManager handles      │
  │                              │   (NOT routed to P2P)      │
  │                              │                            │
  │                              │   - Set remote description │
  │                              │   - Add audio/video tracks │
  │                              │   - Create answer          │
  │                              │                            │
  │                              │ 5. ANSWER                  │
  │                              │    callId: "call-123456"   │
  │                              ◄────────────────────────────┤
  │ 6. ANSWER received           │                            │
  ◄──────────────────────────────┤                            │
  │                              │                            │
  │ CallManager handles          │                            │
  │ - Set remote description     │                            │
  │                              │                            │
  │ 7. ICE_CANDIDATE             │                            │
  │    callId: "call-123456"     │                            │
  │    (NO "p2p-" prefix)        │                            │
  ├──────────────────────────────►                            │
  │                              ├────────────────────────────►
  │                              │                            │
  │                              │ 8. ICE_CANDIDATE           │
  │                              ◄────────────────────────────┤
  ◄──────────────────────────────┤                            │
  │                              │                            │
  │ 9. ICE Connected             │                            │
  │    Audio/Video streaming     │      Audio/Video streaming │
  │                              │                            │
```

## Signal Routing Logic

### CallManager.handleIncomingSignal() Decision Tree

```
                    Incoming WebRTC Signal
                            │
                            ▼
              ┌─────────────────────────┐
              │ Extract signal.type     │
              │ Extract signal.callId   │
              └────────────┬────────────┘
                           │
                           ▼
          ┌────────────────────────────────┐
          │ Signal Type Check              │
          └────┬───────────────────────┬───┘
               │                       │
               ▼                       ▼
     ┌─────────────────┐    ┌──────────────────┐
     │ P2P_OFFER?      │    │ P2P_ANSWER?      │
     │ YES → ROUTE P2P │    │ YES → ROUTE P2P  │
     └─────────────────┘    └──────────────────┘
               │                       │
               ▼                       ▼
         ┌──────────────────────────────────────┐
         │ P2PConnectionManager                 │
         │ .handleIncomingSignal(signal)        │
         └──────────────────────────────────────┘
                           
               │
               ▼
     ┌──────────────────┐
     │ ICE_CANDIDATE?   │
     │                  │
     └────┬─────────┬───┘
          │         │
          ▼         ▼
    ┌─────────┐ ┌──────────┐
    │ callId  │ │ callId   │
    │ starts  │ │ NO "p2p-"│
    │ "p2p-"? │ │ prefix   │
    │ YES     │ │          │
    └────┬────┘ └─────┬────┘
         │            │
         ▼            ▼
    ┌────────────┐ ┌───────────────────┐
    │ ROUTE P2P  │ │ Handle in         │
    │            │ │ CallManager       │
    │ P2PConn    │ │ (Voice/Video ICE) │
    │ Manager    │ │                   │
    └────────────┘ └───────────────────┘
         
               │
               ▼
     ┌──────────────────┐
     │ OFFER, ANSWER,   │
     │ HANGUP, etc.     │
     │                  │
     └────┬─────────────┘
          │
          ▼
    ┌───────────────────────┐
    │ Handle in CallManager │
    │ (Voice/Video Calls)   │
    └───────────────────────┘
```

## CallId Format Comparison

### P2P Messaging CallId
```
Format: "p2p-" + targetUsername + "-" + timestamp

Examples:
- "p2p-alice-1704891234567"
- "p2p-bob-1704891234789"
- "p2p-charlie-1704891235012"

Purpose: Routes P2P messaging signals to P2PConnectionManager
```

### Voice/Video Call CallId
```
Format: Normal string without "p2p-" prefix

Examples:
- "call-1704891234567"
- "vc-alice-bob-1704891234789"
- Any string without "p2p-" prefix

Purpose: Handled by CallManager for voice/video calls
```

## Key Components

### 1. WebRTCSignalingClient (Shared Instance)
```java
public class WebRTCSignalingClient {
    private SignalCallback callback;  // Only ONE callback set
    
    public void sendSignalViaStream(WebRTCSignal signal) {
        // Send to server
    }
    
    // Called when signal received from server
    private void onSignalReceived(WebRTCSignal signal) {
        if (callback != null) {
            callback.onSignalReceived(signal);
            // ↑ Always goes to CallManager.handleIncomingSignal()
        }
    }
}
```

### 2. CallManager (Signal Router)
```java
public class CallManager {
    private WebRTCSignalingClient signalingClient;  // Owner
    
    public void handleIncomingSignal(WebRTCSignal signal) {
        SignalType type = signal.getType();
        String callId = signal.getCallId();
        
        // ROUTING LOGIC
        boolean isP2PSignal = 
            (type == P2P_OFFER || type == P2P_ANSWER ||
             (type == ICE_CANDIDATE && callId != null && 
              callId.startsWith("p2p-")));
        
        if (isP2PSignal) {
            // Route to P2PConnectionManager
            P2PConnectionManager.getInstance()
                .handleIncomingSignal(signal);
            return;
        }
        
        // Handle voice/video signals normally
        // ... existing voice/video logic ...
    }
}
```

### 3. P2PConnectionManager (DataChannel Handler)
```java
public class P2PConnectionManager {
    private WebRTCSignalingClient signalingClient;  // Shared from CallManager
    
    public void initialize() {
        // Share CallManager's signaling client
        CallManager callManager = CallManager.getInstance();
        this.signalingClient = callManager.getSignalingClient();
    }
    
    public void handleIncomingSignal(WebRTCSignal signal) {
        switch (signal.getType()) {
            case P2P_OFFER:
                handleP2POffer(signal);
                break;
            case P2P_ANSWER:
                handleP2PAnswer(signal);
                break;
            case ICE_CANDIDATE:
                handleIceCandidate(signal);
                break;
        }
    }
}
```

## Benefits of This Architecture

1. **No Callback Conflicts:**
   - Single `WebRTCSignalingClient` instance
   - Single callback: `CallManager.handleIncomingSignal()`
   - Routing at CallManager level

2. **Clean Separation:**
   - Voice/video: `CallManager` (OFFER/ANSWER, no callId prefix)
   - P2P messaging: `P2PConnectionManager` (P2P_OFFER/P2P_ANSWER, "p2p-" callId)

3. **Voice/Video Calls Untouched:**
   - No changes to existing call signaling
   - ICE candidates without "p2p-" prefix processed normally
   - Call functionality completely preserved

4. **Scalable:**
   - Easy to add more P2P features (file transfer, screen sharing)
   - Just use "p2p-" callId prefix and route through CallManager
   - No new signaling clients needed

5. **Debuggable:**
   - Clear signal type distinction
   - CallId prefix makes routing obvious
   - Easy to trace signal flow in logs
