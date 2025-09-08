package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.UDPHoleGrpc;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.server.SafeRoomServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;

public class ClientMenu{
	public static String Server = SafeRoomServer.ServerIP;
	public static int Port = SafeRoomServer.grpcPort;
	public static int UDP_Port = SafeRoomServer.udpPort1;
	public static String myUsername = "abkarada";
	public static String target_username = "james";

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

}