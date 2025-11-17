# Screen Sharing Fix - Summary

## Problem
When screen sharing was activated in a 1-1 call, the peer's camera video would completely disappear ("karşıdakinin tüm görüntüsü yok oluyor no video geliyor"). The issue was caused by attempting to add a second video track to the same peer connection.

## Root Cause
The original implementation tried to add the screen share track as a **separate video track** using `addTrack()` with a different stream ID ("screen_share_stream"). However:

1. **WebRTC's Unified Plan** by default uses ONE transceiver per media type (one for audio, one for video)
2. When `addTrack()` was called for the screen share, WebRTC would **reuse the existing video transceiver** instead of creating a new one
3. This caused the camera video track to be **replaced** rather than coexisting with the screen share track
4. The remote peer would only receive the screen share video, losing the camera feed

## Solution
Implemented the **standard WebRTC approach** for screen sharing using `replaceTrack()`:

### Changes Made

#### 1. WebRTCClient.java
- **Added `videoSender` field** to track the video RTP sender
- **Modified `addVideoTrack()`**: Store the `RTCRtpSender` when adding camera video track
- **Modified `startScreenShare()`**: Use `videoSender.replaceTrack(screenShareTrack)` to replace camera with screen share
- **Modified `stopScreenShare()`**: Use `videoSender.replaceTrack(localVideoTrack)` to restore camera video

#### 2. CallManager.java
- **Updated `renegotiateWithScreenShare()`**: Clearer logging indicating camera is being replaced
- **Added `renegotiateAfterScreenShareStop()`**: Renegotiate when screen share stops to notify peer about camera restoration
- **Modified `stopScreenShare()`**: Call `renegotiateAfterScreenShareStop()` after stopping screen share

#### 3. ActiveCallDialog.java
- **Simplified `attachRemoteVideo()`**: Since the same track (video0) is used for both camera and screen share (via replaceTrack), we no longer need to detect track type
- **Removed screen share detection logic**: The track content changes (camera → screen → camera) but the track ID remains constant

## How It Works Now

### Starting Screen Share:
1. User clicks screen share button
2. `CallManager.startScreenShare()` → `WebRTCClient.startScreenShare()`
3. `videoSender.replaceTrack(screenShareTrack)` replaces camera with screen share
4. `renegotiateWithScreenShare()` creates new SDP offer with screen share video
5. Remote peer receives SCREEN_SHARE_OFFER and updates their view
6. **Remote peer continues to see video** - now showing screen instead of camera

### Stopping Screen Share:
1. User stops screen sharing
2. `CallManager.stopScreenShare()` → `WebRTCClient.stopScreenShare()`
3. `videoSender.replaceTrack(localVideoTrack)` restores camera video
4. `renegotiateAfterScreenShareStop()` notifies remote peer
5. Remote peer receives SCREEN_SHARE_STOP and continues viewing camera
6. **Camera video is automatically restored** - smooth transition

## Benefits

1. ✅ **No video loss**: Remote peer always sees video (camera OR screen share)
2. ✅ **Standard approach**: Uses WebRTC's recommended `replaceTrack()` method
3. ✅ **Seamless transition**: Switching between camera and screen share is smooth
4. ✅ **Single transceiver**: Works with WebRTC's default Unified Plan behavior
5. ✅ **Automatic restoration**: Camera video restored when screen share stops

## Testing Recommendations

1. **Basic screen share**:
   - Start 1-1 call
   - Share screen
   - Verify remote peer sees screen (not black screen)
   - Stop screen share
   - Verify remote peer sees camera again

2. **Screen share switching**:
   - Share screen
   - Switch to different screen/window
   - Verify remote peer sees the new screen

3. **Multiple cycles**:
   - Start/stop screen share multiple times
   - Verify camera video always restores correctly

## Known Limitations

- **One video source at a time**: Can't show camera AND screen share simultaneously
- This is the standard WebRTC behavior and matches Zoom/Discord/Meet
- For simultaneous video + screen share, would need separate peer connections or simulcast

## Notes for Future (Group Calls)

For group calls with `GroupCallManager`, the same `replaceTrack()` approach should be used:
- Each peer connection maintains one video transceiver
- Screen share replaces camera video on that connection
- All peers receive either camera OR screen share video
- Consider adding UI indicator to show which peer is screen sharing
