package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.UDPHoleGrpc;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.server.SafeRoomServer;
import com.saferoom.natghost.NatAnalyzer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class ClientMenu{
	public static String Server = SafeRoomServer.ServerIP;
	public static int Port = SafeRoomServer.grpcPort;
	public static int UDP_Port = SafeRoomServer.udpPort1;

		public static String Login(String username, String Password)
		{
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();

			UDPHoleGrpc.UDPHoleBlockingStub client = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
			SafeRoomProto.Menu main_menu = SafeRoomProto.Menu.newBuilder()
				.setUsername(username)
				.setHashPassword(Password)
				.build();
			SafeRoomProto.Status stats = client.menuAns(main_menu);
			
			String message = stats.getMessage();
			int code = stats.getCode();
			switch(code){
				case 0:
					System.out.println("Success!");
					System.out.printf("✅ Logged in as: %s%n", username);
					return message; // Server'dan gelen eksik bilgiyi döndür (email veya username)
				case 1:
					if(message.equals("N_REGISTER")){
						System.out.println("Not Registered");
						return "N_REGISTER";
					}else if(message.equals("WRONG_PASSWORD")){
						System.out.println("Wrong Password");
						return "WRONG_PASSWORD";
						}else{
							System.out.println("Blocked User");
							return "BLOCKED_USER";
						}
			default:
					System.out.println("Message has broken");
					return "ERROR";					
				}
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
		}
	public static int register_client(String username, String password, String mail)
	{
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();

			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Create_User insert_obj = SafeRoomProto.Create_User.newBuilder()
				.setUsername(username)
				.setEmail(mail)
				.setPassword(password)
				.setIsVerified(false)
				.build();
			SafeRoomProto.Status stat = stub.insertUser(insert_obj);

			int code = stat.getCode();
			String message = stat.getMessage();
			
			switch(code){
				case 0:
					System.out.println("Success!");
					return 0;
				case 2:
					if(message.equals("VUSERNAME")){
						System.out.println("Username already taken");
						return 1;
					}else{
						System.out.println("Invalid E-mail");
						return 2;
					}
				default:
					System.out.println("Message has broken");
					return 3;					
			}
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}
	public static int verify_user(String username, String verify_code) {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
					.usePlaintext()
					.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(channel)
					.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			Verification verification_info = Verification.newBuilder()
					.setUsername(username)
					.setVerify(verify_code)
					.build();
			
			SafeRoomProto.Status response = stub.verifyUser(verification_info);
			
			int code = response.getCode();
			
			switch(code) {
			case 0:
				System.out.println("Verification Completed");
				return 0;
			case 1:
				System.out.println("Not Matched");
				return 1;
			
			default:
				System.out.println("Connection is not safe");
				return 2;
			}
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	public static boolean verify_email(String mail){
		ManagedChannel channel = null;
		try{
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();

			UDPHoleGrpc.UDPHoleBlockingStub client = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(mail)
				.build();
			
			SafeRoomProto.Status status = client.verifyEmail(request);

			int code = status.getCode();

			if(code == 1){
				return true;
			}
			
		}catch(Exception e){
			System.err.println("Verify Channel Error: " + e);
		}finally{
			if(channel != null){
				channel.shutdown();
			}
		}

		return false;
	}

	public static int changePassword(String email, String newPassword) {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(channel);
			
			// Format: "email:newpassword"
			String requestData = email + ":" + newPassword;
			Request_Client request = Request_Client.newBuilder()
				.setUsername(requestData)
				.build();
			
			Status response = stub.changePassword(request);
			int code = response.getCode();
			String message = response.getMessage();
			
			System.out.println("Change Password Response: " + message + " (Code: " + code + ")");
			
			return code;
			
		} catch (Exception e) {
			System.err.println("Change Password Channel Error: " + e);
			return 2; // Error code
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	public static java.util.List<java.util.Map<String, Object>> searchUsers(String searchTerm, String currentUser) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.SearchRequest request = SafeRoomProto.SearchRequest.newBuilder()
				.setSearchTerm(searchTerm)
				.setCurrentUser(currentUser)
				.build();
				
			SafeRoomProto.SearchResponse response = blockingStub.searchUsers(request);
			
			java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
			if (response.getSuccess()) {
				for (SafeRoomProto.UserResult user : response.getUsersList()) {
					java.util.Map<String, Object> userMap = new java.util.HashMap<>();
					userMap.put("username", user.getUsername());
					userMap.put("email", user.getEmail());
					userMap.put("isOnline", user.getIsOnline());
					userMap.put("lastSeen", user.getLastSeen());
					userMap.put("is_friend", user.getIsFriend());
					userMap.put("has_pending_request", user.getHasPendingRequest());
					
					// Debug log
					System.out.println("🔍 Search Result for " + user.getUsername() + ":");
					System.out.println("  - is_friend: " + user.getIsFriend());
					System.out.println("  - has_pending_request: " + user.getHasPendingRequest());
					
					results.add(userMap);
				}
			}
			return results;
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	public static SafeRoomProto.ProfileResponse getProfile(String targetUsername, String currentUser) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.ProfileRequest request = SafeRoomProto.ProfileRequest.newBuilder()
				.setUsername(targetUsername)
				.setRequestedBy(currentUser)
				.build();
				
			SafeRoomProto.ProfileResponse response = blockingStub.getProfile(request);
			return response;
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	public static SafeRoomProto.FriendResponse sendFriendRequest(String fromUser, String toUser) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequest request = SafeRoomProto.FriendRequest.newBuilder()
				.setSender(fromUser)
				.setReceiver(toUser)
				.setMessage("") // Boş mesaj
				.build();
				
			SafeRoomProto.FriendResponse response = blockingStub.sendFriendRequest(request);
			return response;
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	// ===============================
	// FRIEND SYSTEM CLIENT METHODS
	// ===============================

	/**
	 * Bekleyen arkadaşlık isteklerini getir (gelen istekler)
	 */
	public static SafeRoomProto.PendingRequestsResponse getPendingFriendRequests(String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getPendingFriendRequests(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Gönderilen arkadaşlık isteklerini getir (giden istekler)
	 */
	public static SafeRoomProto.SentRequestsResponse getSentFriendRequests(String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getSentFriendRequests(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Arkadaşlık isteğini kabul et
	 */
	public static SafeRoomProto.Status acceptFriendRequest(int requestId, String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.acceptFriendRequest(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Arkadaşlık isteğini reddet
	 */
	public static SafeRoomProto.Status rejectFriendRequest(int requestId, String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.rejectFriendRequest(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Gönderilen arkadaşlık isteğini iptal et
	 */
	public static SafeRoomProto.Status cancelFriendRequest(int requestId, String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.cancelFriendRequest(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Arkadaş listesini getir
	 */
	public static SafeRoomProto.FriendsListResponse getFriendsList(String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getFriendsList(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Arkadaşı kaldır
	 */
	public static SafeRoomProto.Status removeFriend(String user1, String user2) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.RemoveFriendRequest request = SafeRoomProto.RemoveFriendRequest.newBuilder()
				.setUser1(user1)
				.setUser2(user2)
				.build();
				
			return blockingStub.removeFriend(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Arkadaşlık istatistiklerini getir
	 */
	public static SafeRoomProto.FriendshipStatsResponse getFriendshipStats(String username) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getFriendshipStats(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * Heartbeat gönder
	 */
	public static SafeRoomProto.HeartbeatResponse sendHeartbeat(String username, String sessionId) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(5, TimeUnit.SECONDS);
			
			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
				.setUsername(username)
				.setSessionId(sessionId)
				.build();
				
			return blockingStub.sendHeartbeat(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}

	/**
	 * User session'ını sonlandır
	 */
	public static SafeRoomProto.Status endUserSession(String username, String sessionId) throws Exception {
		ManagedChannel channel = null;
		try {
			channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.build();
			
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(channel)
				.withDeadlineAfter(5, TimeUnit.SECONDS);
			
			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
				.setUsername(username)
				.setSessionId(sessionId)
				.build();
				
			return blockingStub.endUserSession(request);
		} finally {
			if (channel != null) {
				channel.shutdown();
			}
		}
	}
	
	// ============================================
	// P2P HOLE PUNCHING METHODS
	// ============================================
	
	/**
	 * Register user with P2P signaling server on application startup
	 * @param username Username to register
	 * @return true if registration successful
	 */
	public static boolean registerP2PUser(String username) {
		try {
			System.out.println("[P2P] Registering user with server: " + username);
			
			InetSocketAddress signalingServer = new InetSocketAddress(Server, UDP_Port); // P2PSignalingServer.SIGNALING_PORT
			boolean registered = NatAnalyzer.registerWithServer(username, signalingServer);
			
			if (registered) {
				// Initialize reliable messaging protocol
				System.out.println("[P2P] 🔧 Initializing reliable messaging protocol...");
				NatAnalyzer.initializeReliableMessaging(username);
				
				// Set callback for received messages
				NatAnalyzer.setReliableMessageCallback((sender, message) -> {
					System.out.printf("[P2P-CALLBACK] 📨 Received from %s: \"%s\"%n", sender, message);
					
					// Forward to ChatService GUI
					javafx.application.Platform.runLater(() -> {
						try {
							com.saferoom.gui.service.ChatService.getInstance()
								.receiveP2PMessage(sender, username, message);
						} catch (Exception e) {
							System.err.println("[P2P-CALLBACK] Error forwarding to GUI: " + e.getMessage());
						}
					});
				});
				
				System.out.println("[P2P] ✅ Reliable messaging initialized for: " + username);
				
				// 📁 Set callback for incoming file transfers
				System.out.println("[P2P] 🔧 Registering file transfer callback...");
				NatAnalyzer.setFileTransferCallback(new NatAnalyzer.FileTransferCallback() {
					@Override
					public void onFileTransferRequest(String sender, long fileId, String fileName, long fileSize, int totalChunks) {
						System.out.printf("[P2P-FILE-CALLBACK] 📁 Incoming file from %s: %s (%d bytes)%n", 
							sender, fileName, fileSize);
						
						// Show FileTransferDialog on JavaFX thread
						javafx.application.Platform.runLater(() -> {
							try {
								com.saferoom.gui.dialog.FileTransferDialog dialog = 
									new com.saferoom.gui.dialog.FileTransferDialog(
										sender, 
										fileId,
										fileName, 
										fileSize
									);
								
								java.util.Optional<java.nio.file.Path> result = dialog.showAndWait();
								
								if (result.isPresent()) {
									// User accepted - call NatAnalyzer.acceptFileTransfer()
									java.nio.file.Path savePath = result.get();
									System.out.printf("[P2P-FILE-CALLBACK] ✅ User accepted file - saving to: %s%n", 
										savePath);
									
									// Accept file transfer
									NatAnalyzer.acceptFileTransfer(sender, fileId, savePath);
									
									// Show success message in chat
									com.saferoom.gui.service.ChatService.getInstance()
										.receiveP2PMessage(
											sender, 
											username, 
											String.format("📎 Receiving file: %s", fileName)
										);
									
								} else {
									// User declined
									System.out.printf("[P2P-FILE-CALLBACK] ❌ User declined file from %s%n", sender);
									
									// TODO: Send rejection notification to sender
								}
								
							} catch (Exception e) {
								System.err.println("[P2P-FILE-CALLBACK] Error showing file dialog: " + e.getMessage());
								e.printStackTrace();
							}
						});
					}
					
				@Override
				public void onFileTransferComplete(String peer, long fileId, java.nio.file.Path filePath) {
					System.out.printf("[P2P-FILE-CALLBACK] ✅ File transfer complete: %s%n", filePath);
					
					// Send "File received" confirmation to sender via P2P
					String confirmationMessage = String.format("✅ File received: %s", filePath.getFileName());
					
					try {
						com.saferoom.natghost.NatAnalyzer.sendReliableMessage(peer, confirmationMessage);
						System.out.printf("[P2P-FILE-CALLBACK] 📤 Sent confirmation to %s%n", peer);
					} catch (Exception e) {
						System.err.println("[P2P-FILE-CALLBACK] Failed to send confirmation: " + e.getMessage());
					}
				}					@Override
					public void onFileTransferError(String peer, long fileId, Exception error) {
						System.err.printf("[P2P-FILE-CALLBACK] ❌ File transfer error: %s%n", error.getMessage());
						
						javafx.application.Platform.runLater(() -> {
							javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
								javafx.scene.control.Alert.AlertType.ERROR);
							alert.setTitle("File Transfer Error");
							alert.setHeaderText("File Transfer Failed");
							alert.setContentText(error.getMessage());
							alert.show();
					});
				}
				
				@Override
				public void onFileTransferProgress(String peer, long fileId, int current, int total) {
					// Log every chunk (for debugging)
					System.out.printf("[P2P-FILE-CALLBACK] 📊 Progress: %d/%d chunks%n", current, total);
					
					// Update GUI only at 25%, 50%, 75% milestones
					int progressPercent = (current * 100) / total;
					int lastMilestone = ((current - 1) * 100) / total;
					
					// Check if we just crossed a 25% milestone
					if (progressPercent / 25 > lastMilestone / 25) {
						javafx.application.Platform.runLater(() -> {
							String progressMsg = String.format("⏳ Progress: %d%% (%d/%d chunks)", 
								progressPercent, current, total);
							System.out.printf("[P2P-FILE-CALLBACK] � Milestone: %s%n", progressMsg);
							// Note: Not adding to chat to avoid spam
							// Could add a single progress message and update it (advanced UI)
						});
					}
				}
			});				System.out.println("[P2P] ✅ File transfer callback registered");
			}
			
			return registered;
			
		} catch (Exception e) {
			System.err.println("[P2P] Error during user registration: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * 🆕 ASYNC NON-BLOCKING P2P Connection Request
	 * Start hole punch asynchronously and return CompletableFuture
	 * 
	 * @param myUsername Current user's username
	 * @param targetUsername Target user to connect to
	 * @return CompletableFuture<Boolean> that completes when P2P establishes
	 */
	public static java.util.concurrent.CompletableFuture<Boolean> startP2PHolePunchAsync(String myUsername, String targetUsername) {
		try {
			System.out.println("[P2P] 🚀 Initiating ASYNC P2P request: " + myUsername + " -> " + targetUsername);
			
			// Create signaling server address
			InetSocketAddress signalingServer = new InetSocketAddress(Server, UDP_Port);
			
			// Use new async unidirectional P2P request system
			return NatAnalyzer.requestP2PConnectionAsync(myUsername, targetUsername, signalingServer)
				.thenApply(success -> {
					if (success) {
						System.out.println("[P2P] ✅ Async P2P connection successful");
					} else {
						System.out.println("[P2P] ❌ Async P2P failed - will use server relay");
					}
					return success;
				})
				.exceptionally(e -> {
					System.err.println("[P2P] ❌ Error during async P2P: " + e.getMessage());
					return false;
				});
			
		} catch (Exception e) {
			System.err.println("[P2P] ❌ Error initializing async P2P: " + e.getMessage());
			e.printStackTrace();
			return java.util.concurrent.CompletableFuture.completedFuture(false);
		}
	}
	
	/**
	 * 🔴 DEPRECATED - Start P2P hole punching process with target user (BLOCKING VERSION)
	 * Use startP2PHolePunchAsync() instead to avoid ForkJoinPool exhaustion
	 * 
	 * @param myUsername Current user's username
	 * @param targetUsername Target user to connect to
	 * @return true if hole punch successful, false if should use server relay
	 * @deprecated Use {@link #startP2PHolePunchAsync(String, String)} instead
	 */
	@Deprecated
	public static boolean startP2PHolePunch(String myUsername, String targetUsername) {
		try {
			System.out.println("[P2P] Initiating UNIDIRECTIONAL P2P request: " + myUsername + " -> " + targetUsername);
			
			// Create signaling server address - use correct P2P signaling port
			InetSocketAddress signalingServer = new InetSocketAddress(Server, UDP_Port); // P2PSignalingServer.SIGNALING_PORT
			
			// Use new unidirectional P2P request system
			boolean success = NatAnalyzer.requestP2PConnection(myUsername, targetUsername, signalingServer);
			
			if (success) {
				System.out.println("[P2P] ✅ Unidirectional P2P connection successful");
				return true;
			} else {
				System.out.println("[P2P] ❌ Unidirectional P2P failed - will use server relay");
				return false;
			}
			
		} catch (Exception e) {
			System.err.println("[P2P] Error during unidirectional P2P: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Legacy hole punch method for backward compatibility
	 * @param myUsername Current user's username
	 * @param targetUsername Target user to connect to
	 * @return true if hole punch successful, false if should use server relay
	 */
	public static boolean startLegacyP2PHolePunch(String myUsername, String targetUsername) {
		try {
			System.out.println("[P2P] Initiating LEGACY hole punch: " + myUsername + " -> " + targetUsername);
			
			// Create signaling server address - use correct P2P signaling port
			InetSocketAddress signalingServer = new InetSocketAddress(Server, UDP_Port); // P2PSignalingServer.SIGNALING_PORT
			
			// Perform hole punch using NatAnalyzer (old method)
			boolean success = NatAnalyzer.performHolePunch(myUsername, targetUsername, signalingServer);
			
			if (success) {
				System.out.println("[P2P] ✅ Legacy hole punch successful - P2P connection established");
				return true;
			} else {
				System.out.println("[P2P] ❌ Legacy hole punch failed - will use server relay");
				return false;
			}
			
		} catch (Exception e) {
			System.err.println("[P2P] Error during legacy hole punch: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Analyze NAT type for current network
	 * @return NAT type: 0x00=Full Cone/Restricted, 0x11=Symmetric, 0xFE=Error
	 */
	public static byte analyzeNAT() {
		try {
			System.out.println("[P2P] Analyzing NAT type...");
			byte natType = NatAnalyzer.analyzeSinglePort(NatAnalyzer.stunServers);
			
			String natTypeStr = switch (natType) {
				case (byte)0x00 -> "Full Cone/Restricted NAT (P2P Friendly)";
				case (byte)0x11 -> "Symmetric NAT (P2P Challenging)";
				case (byte)0xFE -> "NAT Analysis Failed";
				default -> "Unknown NAT Type";
			};
			
			System.out.println("[P2P] NAT Type: " + natTypeStr);
			return natType;
			
		} catch (Exception e) {
			System.err.println("[P2P] NAT analysis error: " + e.getMessage());
			return (byte)0xFE;
		}
	}
	
	/**
	 * Get current public IP and port info
	 * @return String array [publicIP, publicPort, natType] or null if failed
	 */
	public static String[] getPublicInfo() {
		try {
			byte natType = analyzeNAT();
			if (natType == (byte)0xFE || NatAnalyzer.myPublicIP == null || NatAnalyzer.Public_PortList.isEmpty()) {
				return null;
			}
			
			return new String[] {
				NatAnalyzer.myPublicIP,
				String.valueOf(NatAnalyzer.Public_PortList.get(0)),
				String.format("0x%02X", natType)
			};
			
		} catch (Exception e) {
			System.err.println("[P2P] Error getting public info: " + e.getMessage());
			return null;
		}
	}
	
	/**
	 * Send P2P message if connection is active with specific peer
	 * @param sender Current user's username
	 * @param receiver Target user's username  
	 * @param message Text message to send
	 * @return true if sent via P2P, false if should use server relay
	 */
	public static boolean sendP2PMessage(String sender, String receiver, String message) {
		if (NatAnalyzer.isP2PActive(receiver)) {
			return NatAnalyzer.sendP2PMessage(sender, receiver, message);
		} else {
			System.out.printf("[P2P] No active P2P connection with %s - use server relay%n", receiver);
			return false;
		}
	}
	
	/**
	 * Check if P2P messaging is available with specific peer
	 */
	public static boolean isP2PMessagingAvailable(String username) {
		return NatAnalyzer.isP2PActive(username);
	}
	
	/**
	 * Check if any P2P messaging is available
	 */
	public static boolean isP2PMessagingAvailable() {
		return NatAnalyzer.isP2PActive();
	}

}


	
