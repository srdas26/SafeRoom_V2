package com.saferoom.grpc;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.saferoom.crypto.CryptoUtils;
import com.saferoom.crypto.KeyExchange;
import com.saferoom.crypto.VerificationCodeGenerator;
import com.saferoom.grpc.SafeRoomProto.FromTo;
import com.saferoom.grpc.SafeRoomProto.Menu;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.SafeRoomProto.Status.Builder;
import com.saferoom.grpc.SafeRoomProto.Stun_Info;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.server.MessageForwarder;
import com.saferoom.grpc.SafeRoomProto.Create_User;
import com.saferoom.grpc.SafeRoomProto.DecryptedPacket;
import com.saferoom.grpc.SafeRoomProto.EncryptedAESKeyMessage;
import com.saferoom.grpc.SafeRoomProto.EncryptedPacket;
import com.saferoom.grpc.SafeRoomProto.SearchRequest;
import com.saferoom.grpc.SafeRoomProto.SearchResponse;
import com.saferoom.grpc.SafeRoomProto.UserResult;
import com.saferoom.db.*;
import com.saferoom.email.EmailSender;
import com.saferoom.sessions.*;
import com.saferoom.grpc.SafeRoomProto.ProfileRequest;
import com.saferoom.grpc.SafeRoomProto.ProfileResponse;
import com.saferoom.grpc.SafeRoomProto.UserProfile;
import com.saferoom.grpc.SafeRoomProto.UserStats;
import com.saferoom.grpc.SafeRoomProto.UserActivity;
import com.saferoom.grpc.SafeRoomProto.FriendRequest;
import com.saferoom.grpc.SafeRoomProto.FriendResponse;
import java.text.SimpleDateFormat;
import java.sql.Timestamp;


import io.grpc.stub.StreamObserver;

public class UDPHoleImpl extends UDPHoleGrpc.UDPHoleImplBase {
	
	@Override
	public void menuAns(Menu request, StreamObserver<Status> response){
	String usernameOrEmail = request.getUsername(); // Artık username veya email olabilir
	String hash_password = request.getHashPassword();
	try {
	boolean user_exist = DBManager.userExists(usernameOrEmail);
		if(user_exist){

			if(!DBManager.isUserBlocked(usernameOrEmail)){ // ! eklendi (blocked değilse)
				
				if(DBManager.verifyPassword(usernameOrEmail, hash_password)){
					// Doğru şifre: attempt sayısını sıfırla
					DBManager.resetLoginAttempts(usernameOrEmail);
					
					// Kullanıcının email ile mi username ile mi giriş yaptığını kontrol et
					String responseMessage;
					if (usernameOrEmail.contains("@")) {
						// Email ile giriş yapmış, username'i döndür
						String username = DBManager.getUsernameByEmail(usernameOrEmail);
						responseMessage = username != null ? username : "UNKNOWN_USER";
					} else {
						// Username ile giriş yapmış, email'i döndür  
						String email = DBManager.getEmailByUsername(usernameOrEmail);
						responseMessage = email != null ? email : "UNKNOWN_EMAIL";
					}

					Status stat = Status.newBuilder()
						.setMessage(responseMessage) // "ALL_GOOD" yerine eksik bilgiyi gönder
						.setCode(0)
						.build();
					DBManager.updateLastLogin(usernameOrEmail);
					response.onNext(stat);
				}else{
					// Yanlış şifre: attempt sayısını artır
					DBManager.updateLoginAttempts(usernameOrEmail);
					Status stat = Status.newBuilder()
						.setMessage("WRONG_PASSWORD")
						.setCode(1)
						.build();
					response.onNext(stat);
				}
			}else{
				Status blocked_stat = Status.newBuilder()
					.setMessage("BLOCKED")
					.setCode(1)
					.build();
				response.onNext(blocked_stat);
			}
		}else{
			Status not_ex = Status.newBuilder()
				.setMessage("N_REGISTER")
				.setCode(1)
				.build();
			response.onNext(not_ex);
		}
		response.onCompleted();	
	
	}catch(Exception e ) {
		System.out.println("DB Error Accoured: " + e);
		response.onError(e);

	}
	}
	
	@Override
	public void insertUser(Create_User request, StreamObserver<Status> response){
		String username = request.getUsername();
		String email =  request.getEmail();
		String password = request.getPassword();
		// verification_code ve is_verified kullanılmıyor, kaldırıldı
	
		boolean is_mail_valid = true;
		try {
			is_mail_valid = DBManager.check_email(email);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			response.onError(e);

		}
	
		if(is_mail_valid == false){
			try {
				if(DBManager.createUser(username, password, email))
				{
					// Her kullanıcı için yeni verification code üret
					String verificationCode = VerificationCodeGenerator.generateVerificationCode();
					DBManager.setVerificationCode(username, verificationCode);
					
					// Yeni HTML template ile email gönder
					if(EmailSender.sendVerificationEmail(email, username, verificationCode)) {
						System.out.println("Successfully Registered and verification email sent!");
					}
					Status stat = Status.newBuilder()
						.setMessage("SUCCESS")
						.setCode(0)
						.build();
					response.onNext(stat);	
				}
				else{
					System.out.println("Username already exists");
					Status not_valid = Status.newBuilder()
						.setMessage("VUSERNAME")
						.setCode(2)
						.build();
					response.onNext(not_valid);
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				response.onError(e);

			}
	
		}
		else{
			System.out.println("Email is already in use");
			Status invalid_mail = Status.newBuilder()
				.setMessage("INVALID_MAIL")
				.setCode(2)
				.build();
			response.onNext(invalid_mail);
		}
			response.onCompleted();
	}
	
	@Override 
	public void verifyUser(Verification verify_code, StreamObserver<Status> responseObserver)
	{
		String usernameOrEmail = verify_code.getUsername(); // Artık username veya email olabilir
		String verCode = verify_code.getVerify();
		try {
		String db_search = DBManager.getVerificationCode(usernameOrEmail);
		if(verCode.equals(db_search)) {
			DBManager.Verify(usernameOrEmail);
	        DBManager.updateVerificationAttempts(usernameOrEmail);

			Status situtation = Status.newBuilder()
					.setCode(0)
					.setMessage("MATCH")
					.build();
			responseObserver.onNext(situtation);
		}else {
			Status not_match = Status.newBuilder()
					.setMessage("NOT_MATCH")
					.setCode(1)
					.build();
			responseObserver.onNext(not_match);
		}
		}catch(Exception e) {
			System.out.println(e);
			responseObserver.onError(e);
		}
		responseObserver.onCompleted();
	}
	
	@Override
	public void registerClient(Stun_Info request, StreamObserver<Status> responseObserver) {
	    SessionManager.registerPeer(request.getUsername(), request); 

	    Status response = Status.newBuilder()
	        .setMessage("Client Registered")
	        .setCode(0)
	        .build();

	    responseObserver.onNext(response);
	    responseObserver.onCompleted();
	}
	
	@Override
	public void verifyEmail(Request_Client req, StreamObserver<Status> responseObserver){
		String candicate_email = req.getUsername();
		Status response = null;
		try{
		boolean is_mail_exists = DBManager.check_email(candicate_email);
		if(is_mail_exists){
			 response = Status.newBuilder()
				.setMessage("EXISTS")
				.setCode(0)
				.build();
				
				String username = DBManager.return_usersname_from_email(candicate_email);
				String verificationCode = VerificationCodeGenerator.generateVerificationCode();
				
				if(DBManager.change_verification_code(username, verificationCode)){System.out.println("Code set");}
				else{System.out.println("IT FAILED");}					
					
				if(EmailSender.sendPasswordResetEmail(candicate_email, username, verificationCode)) {
						System.out.println("Successfully Reset Code Sent!");
					}

		}else{
			 response = Status.newBuilder()
				.setMessage("NOT_EXISTS")
				.setCode(1)
				.build();
		}
		responseObserver.onNext(response);
		responseObserver.onCompleted();
		}
		catch(Exception e){
			System.err.println("Database Error[Email Verification]: " + e);
		}
	}

	@Override
	public void changePassword(Request_Client request, StreamObserver<Status> responseObserver){
		String requestData = request.getUsername(); // Format: "email:newpassword"
		
		try {
			// Request formatını parse et
			if (!requestData.contains(":")) {
				Status errorResponse = Status.newBuilder()
					.setMessage("INVALID_FORMAT")
					.setCode(2)
					.build();
				responseObserver.onNext(errorResponse);
				responseObserver.onCompleted();
				return;
			}
			
			String[] parts = requestData.split(":", 2);
			String email = parts[0];
			String newPassword = parts[1];
			
			// Email'in kayıtlı olup olmadığını kontrol et
			if (!DBManager.check_email(email)) {
				Status notFoundResponse = Status.newBuilder()
					.setMessage("EMAIL_NOT_FOUND")
					.setCode(1)
					.build();
				responseObserver.onNext(notFoundResponse);
				responseObserver.onCompleted();
				return;
			}
			
			if (DBManager.change_users_password(email, newPassword)) {
				System.out.println("Password successfully changed for: " + email);
				
				Status successResponse = Status.newBuilder()
					.setMessage("PASSWORD_CHANGED")
					.setCode(0)
					.build();
				responseObserver.onNext(successResponse);
			} else {
				Status failResponse = Status.newBuilder()
					.setMessage("PASSWORD_CHANGE_FAILED")
					.setCode(2)
					.build();
				responseObserver.onNext(failResponse);
			}
			
		} catch (Exception e) {
			System.err.println("Password change error: " + e.getMessage());
			e.printStackTrace();
			
			Status errorResponse = Status.newBuilder()
				.setMessage("DATABASE_ERROR")
				.setCode(2)
				.build();
			responseObserver.onNext(errorResponse);
		}
		
		responseObserver.onCompleted();
	}
	@Override
	public void getStunInfo(Request_Client request, StreamObserver<Stun_Info> responseObserver) {
	    String username = request.getUsername();
	    Stun_Info peerInfo = SessionManager.getPeer(username); 

	    if (peerInfo != null) {
	        responseObserver.onNext(peerInfo);
	    } else {
	        responseObserver.onNext(Stun_Info.newBuilder()
	            .setUsername(username)
	            .setState(false)
	            .build());
	    }

	    responseObserver.onCompleted();
	}

	@Override
	public void searchUsers(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
		try {
			String searchTerm = request.getSearchTerm();
			String currentUser = request.getCurrentUser();
			
			// Debug log
			System.out.println("🔍 User Search Request:");
			System.out.println("  Search Term: '" + searchTerm + "'");
			System.out.println("  Current User: '" + currentUser + "'");
			
			// Minimum 2 karakter kontrolü
			if (searchTerm.length() < 2) {
				System.out.println("❌ Search term too short (less than 2 characters)");
				responseObserver.onNext(SearchResponse.newBuilder()
					.setSuccess(false)
					.setMessage("En az 2 karakter girin")
					.build());
				responseObserver.onCompleted();
				return;
			}
			
			List<java.util.Map<String, Object>> results = DBManager.searchUsers(searchTerm, currentUser, 10);
			
			// Debug: Results log
			System.out.println("📊 Search Results:");
			System.out.println("  Found " + results.size() + " users:");
			for (java.util.Map<String, Object> user : results) {
				System.out.println("    - " + user.get("username") + " (" + user.get("email") + ")");
			}

			SearchResponse.Builder responseBuilder = SearchResponse.newBuilder().setSuccess(true);
				
			for (java.util.Map<String, Object> user : results) {
				responseBuilder.addUsers(UserResult.newBuilder()
					.setUsername((String) user.get("username"))
					.setEmail((String) user.get("email"))
					.setIsOnline(false)
					.setLastSeen(user.get("lastLogin") != null ? user.get("lastLogin").toString() : "")
					.build());
			}
			
			System.out.println("✅ Search completed successfully, sending response to client");
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("❌ Search Error: " + e.getMessage());
			e.printStackTrace();
			responseObserver.onError(e);
		}
	}

	// ===============================
// PROFILE SYSTEM METHODS
// ===============================

@Override
public void getProfile(ProfileRequest request, StreamObserver<ProfileResponse> responseObserver) {
    try {
        String username = request.getUsername();
        String requestedBy = request.getRequestedBy();
        
        System.out.println("📋 Profile request for '" + username + "' by '" + requestedBy + "'");
        
        // Kullanıcı var mı kontrol et
        if (!DBManager.userExists(username)) {
            System.out.println("❌ User '" + username + "' not found");
            responseObserver.onNext(ProfileResponse.newBuilder()
                .setSuccess(false)
                .setMessage("User not found")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Profile bilgilerini al
        java.util.Map<String, Object> profileData = DBManager.getUserProfile(username, requestedBy);
        
        if (profileData == null) {
            System.out.println("❌ Failed to load profile for '" + username + "'");
            responseObserver.onNext(ProfileResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Failed to load profile")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Date formatter
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm");
        
        // Stats bilgilerini al
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> statsData = (java.util.Map<String, Object>) profileData.get("stats");
        
        UserStats stats = UserStats.newBuilder()
            .setRoomsCreated((Integer) statsData.get("roomsCreated"))
            .setRoomsJoined((Integer) statsData.get("roomsJoined"))
            .setFilesShared((Integer) statsData.get("filesShared"))
            .setMessagesSent((Integer) statsData.get("messagesSent"))
            .setSecurityScore((Double) statsData.get("securityScore"))
            .build();
        
        // Activities bilgilerini al
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> activitiesData = 
            (java.util.List<java.util.Map<String, Object>>) profileData.get("activities");
        
        UserProfile.Builder profileBuilder = UserProfile.newBuilder()
            .setUsername((String) profileData.get("username"))
            .setEmail((String) profileData.get("email"))
            .setJoinDate(dateFormat.format((Timestamp) profileData.get("joinDate")))
            .setLastSeen(timeFormat.format((Timestamp) profileData.get("lastSeen")))
            .setIsOnline(false) // TODO: Online status implementation
            .setStats(stats)
            .setFriendStatus((String) profileData.get("friendStatus"));
        
        // Activities ekle
        for (java.util.Map<String, Object> activity : activitiesData) {
            UserActivity userActivity = UserActivity.newBuilder()
                .setActivityType((String) activity.get("activityType"))
                .setDescription((String) activity.get("description"))
                .setTimestamp(timeFormat.format((Timestamp) activity.get("timestamp")))
                .setActivityData(activity.get("activityData") != null ? (String) activity.get("activityData") : "")
                .build();
            profileBuilder.addRecentActivities(userActivity);
        }
        
        // Friend status kontrolü
        String friendStatus = (String) profileData.get("friendStatus");
        profileBuilder.setIsFriend("friends".equals(friendStatus));
        
        UserProfile profile = profileBuilder.build();
        
        System.out.println("✅ Profile loaded for '" + username + "' - Friend status: " + friendStatus);
        
        responseObserver.onNext(ProfileResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Profile loaded successfully")
            .setProfile(profile)
            .build());
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        System.err.println("❌ Profile request error: " + e.getMessage());
        e.printStackTrace();
        responseObserver.onNext(ProfileResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Server error: " + e.getMessage())
            .build());
        responseObserver.onCompleted();
    }
}

@Override
public void sendFriendRequest(FriendRequest request, StreamObserver<FriendResponse> responseObserver) {
    try {
        String sender = request.getSender();
        String receiver = request.getReceiver();
        String message = request.getMessage();
        
        System.out.println("👥 Friend request: '" + sender + "' -> '" + receiver + "'");
        
        // Kullanıcılar var mı kontrol et
        if (!DBManager.userExists(sender) || !DBManager.userExists(receiver)) {
            System.out.println("❌ One or both users not found");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(false)
                .setMessage("User not found")
                .setStatus("error")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Friend request gönder
        boolean success = DBManager.sendFriendRequest(sender, receiver, message);
        
        if (success) {
            System.out.println("✅ Friend request sent successfully");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Friend request sent successfully")
                .setStatus("sent")
                .build());
        } else {
            System.out.println("❌ Friend request failed (already exists or blocked)");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Friend request already exists or users are blocked")
                .setStatus("failed")
                .build());
        }
        
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        System.err.println("❌ Friend request error: " + e.getMessage());
        e.printStackTrace();
        responseObserver.onNext(FriendResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Server error: " + e.getMessage())
            .setStatus("error")
            .build());
        responseObserver.onCompleted();
    }
}

	@Override
	public void punchTest(FromTo request, StreamObserver<Status> responseObserver) {
	    Stun_Info targetInfo = SessionManager.getPeer(request.getThem());

	    Status.Builder responseBuilder = Status.newBuilder();
	    if (targetInfo != null) {
	        responseBuilder.setMessage("Target peer found. Ready to punch.");
	        responseBuilder.setCode(0);
	    } else {
	        responseBuilder.setMessage("Target peer not found.");
	        responseBuilder.setCode(1);
	    }

	    responseObserver.onNext(responseBuilder.build());
	    responseObserver.onCompleted();
	}

	
	@Override
	public void handShake(SafeRoomProto.HandshakeConfirm request, StreamObserver<SafeRoomProto.Status> responseObserver) {
	    String client = request.getClientId();
	    String target = request.getTargetId();
	    long time = request.getTimestamp();

	    System.out.println("[HANDSHAKE] " + client + " ↔ " + target + " @ " + time);

	    // (İleride buraya log database işlemleri eklenebilir)

	    SafeRoomProto.Status response = SafeRoomProto.Status.newBuilder()
	        .setMessage("Handshake logged successfully.")
	        .setCode(0)
	        .build();

	    responseObserver.onNext(response);
	    responseObserver.onCompleted();
	}	    // (İleride buraya log database işlemleri eklenebilir)


	@Override
	public void heartBeat(Stun_Info request, StreamObserver<Status> responseObserver) {
	    String username = request.getUsername();

	    Status status;
	    if (SessionManager.hasPeer(username)) {
	        System.out.println("[HEARTBEAT] Aktif: " + username);
	        status = Status.newBuilder()
	                .setMessage("Peer is active.")
	                .setCode(0)
	                .build();
	    } else {
	        System.out.println("[HEARTBEAT] Peer not found: " + username);
	        status = Status.newBuilder()
	                .setMessage("Peer not found.")
	                .setCode(1)
	                .build();
	    }

	    responseObserver.onNext(status);
	    responseObserver.onCompleted();
	}



	@Override
	public void finish(Request_Client request, StreamObserver<Status> responseObserver) {
	    String username = request.getUsername();
	    Stun_Info removed = SessionManager.getPeer(username);
	    SessionManager.removePeer(username);

	    Status status;
	    if (removed != null) {
	        System.out.println("[FINISH] Peer removed: " + username);
	        status = Status.newBuilder()
	                .setMessage("Peer successfully removed.")
	                .setCode(0)
	                .build();
	    } else {
	        System.out.println("[FINISH] Peer not found for removal: " + username);
	        status = Status.newBuilder()
	                .setMessage("Peer not found.")
	                .setCode(1)
	                .build();
	    }

	    responseObserver.onNext(status);
	    responseObserver.onCompleted();
	}

	@Override
	public void getServerPublicKey(SafeRoomProto.Empty request, StreamObserver<SafeRoomProto.PublicKeyMessage> responseObserver) {
	    byte[] rsa_pub = KeyExchange.publicKey.getEncoded();
	    String publicKeyBase64 = Base64.getEncoder().encodeToString(rsa_pub);

	    SafeRoomProto.PublicKeyMessage response = SafeRoomProto.PublicKeyMessage.newBuilder()
	        .setBase64Key(publicKeyBase64)
	        .build();

	    responseObserver.onNext(response);
	    responseObserver.onCompleted();
	}
	

	@Override
	public void sendEncryptedAESKey(EncryptedAESKeyMessage request, StreamObserver<Status> responseObserver) {
	    try {
	        SecretKey aesKey = CryptoUtils.decrypt_AESkey(request.getEncryptedKey(), KeyExchange.privateKey);
	        String clientId = request.getClientId();

	        SessionInfo session = SessionManager.get(clientId);
	        SessionManager.updateAESKey(clientId, aesKey);
	        
	        if (session != null) {
	            session.setAesKey(aesKey);
	        }

	        Status status = Status.newBuilder()
	            .setMessage("AES key başarıyla çözüldü.")
	            .setCode(0)
	            .build();

	        responseObserver.onNext(status);
	        responseObserver.onCompleted();
	    } catch (Exception e) {
	        Status status = Status.newBuilder()
	            .setMessage("AES çözümleme başarısız: " + e.getMessage())
	            .setCode(2)
	            .build();

	        responseObserver.onNext(status);
	        responseObserver.onCompleted();
	    }
	}


	@Override
	public void sendEncryptedMessage(EncryptedPacket request, StreamObserver<Status> responseObserver) {
	    // from ve to değişkenleri kullanılmıyor, sadece forwardToPeer kullanılıyor

	    MessageForwarder forwarder = new MessageForwarder(SessionManager.getAllPeers()); 
	    boolean success = forwarder.forwardToPeer(request);

	    Status.Builder response = Status.newBuilder();
	    if (success) {
	        response.setMessage("Mesaj başarıyla iletildi.").setCode(0);
	    } else {
	        response.setMessage("Mesaj iletilemedi.").setCode(2);
	    }

	    responseObserver.onNext(response.build());
	    responseObserver.onCompleted();
	}


	@Override
	public void decryptedMessage(EncryptedPacket request, StreamObserver<DecryptedPacket> responseObserver) {
	    String sender = request.getSender();
	    String to = request.getReceiver();
	    String base64EncryptedData = request.getPayload();

	    SessionInfo session = SessionManager.get(sender); 
	    SecretKey aesKey = session != null ? session.getAesKey() : null;

	    String plaintext = "";
	    try {
	        if (aesKey == null) throw new RuntimeException("AES key not found for sender");

	        byte[] decodedData = Base64.getDecoder().decode(base64EncryptedData);
	        byte[] iv = new byte[16];
	        byte[] ciphertext = new byte[decodedData.length - 16];

	        System.arraycopy(decodedData, 0, iv, 0, 16);
	        System.arraycopy(decodedData, 16, ciphertext, 0, ciphertext.length);

	        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
	        cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
	        byte[] decrypted = cipher.doFinal(ciphertext);

	        plaintext = new String(decrypted, StandardCharsets.UTF_8);
	    } catch (Exception e) {
	        System.err.println("Decryption error: " + e.getMessage());
	    }

	    DecryptedPacket response = DecryptedPacket.newBuilder()
	        .setSendedBy(sender)
	        .setRecvedBy(to)
	        .setPlaintext(plaintext)
	        .build();

	    responseObserver.onNext(response);
	    responseObserver.onCompleted();
	}
	
	private static final Map<String, String> PublicKeyManager = new ConcurrentHashMap<>();
	
	@Override
	public  void sendPublicKey(SafeRoomProto.SendPublicKeyRequest request, StreamObserver<Status> response){
			
			String pubKey = request.getBase64Pubkey();
			String Session_ID = request.getSessionID();
			String Starter = request.getStarter();
			String Joiner = request.getJoiner();
			
			SessionManager.register(Session_ID, Starter, Joiner);
			SessionManager.updateRSAKey(Session_ID, pubKey);
			
			PublicKeyManager.put(Session_ID, pubKey);
			
			
			
			
			Builder status = Status.newBuilder();
			if(pubKey != null && !pubKey.isEmpty()){
					status.setMessage("Public Key Successfully sent to client")
						  .setCode(0);
			}
			else{
					status.setMessage("Request taken as a null[ERROR] ")
						  .setCode(2);

			}
			response.onNext(status.build());
			response.onCompleted();	

	}
	
	@Override
	public void getPublicKey(SafeRoomProto.RequestByClient_ID request, StreamObserver<SafeRoomProto.PublicKeyMessage> response) {
		String session_ID = request.getClientId();
		
		if(PublicKeyManager.containsKey(session_ID)) {
		String raw_publickey = PublicKeyManager.get(session_ID);
		
		SafeRoomProto.PublicKeyMessage Public_Key = SafeRoomProto.PublicKeyMessage.newBuilder()
												.setBase64Key(raw_publickey)
												.setUsername(session_ID)
												.build();
		response.onNext(Public_Key);
		response.onCompleted();
		}
		else {
			System.out.println("Public Key Manager not contain your key");
			response.onCompleted();
		}
	}
	
	@Override
	public void getEncryptedAESKey(SafeRoomProto.RequestByClient_ID request, StreamObserver<SafeRoomProto.EncryptedAESKeyMessage> response) {
		String session_ID = request.getClientId();
		
		SessionInfo session = SessionManager.get(session_ID);
	    if (session == null || session.getAesKey() == null) {
	        response.onError(null);
	        return;
	    }

		SecretKey aesKey = session.getAesKey();
		String encodedAES = Base64.getEncoder().encodeToString(aesKey.getEncoded());
		SafeRoomProto.EncryptedAESKeyMessage ReturnedAESKey = SafeRoomProto.EncryptedAESKeyMessage.newBuilder()
																								  .setClientId(session_ID)
																								  .setEncryptedKey(encodedAES)
																								  .build();
		response.onNext(ReturnedAESKey);
		response.onCompleted();
	}
	


}
