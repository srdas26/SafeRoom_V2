package com.saferoom.grpc;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;

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
import com.saferoom.db.*;
import com.saferoom.email.EmailSender;
import com.saferoom.sessions.*;


import io.grpc.stub.StreamObserver;

public class UDPHoleImpl extends UDPHoleGrpc.UDPHoleImplBase {
	
	// Static alanları kaldırıyoruz çünkü her kullanıcı için farklı kod olmalı
	
	@Override
	public void menuAns(Menu request, StreamObserver<Status> response){
	String username = request.getUsername();
	String hash_password = request.getHashPassword();
	try {
	boolean user_exist = DBManager.userExists(username);
		if(user_exist){

			DBManager.updateLoginAttempts(username);

			if(DBManager.isUserBlocked(username)){
				
				if(DBManager.verifyPassword(username, hash_password)){
					Status stat = Status.newBuilder()
						.setMessage("ALL_GOOD")
						.setCode(0)
						.build();
					DBManager.updateLastLogin(username);
					response.onNext(stat);
				}else{
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
		String username = verify_code.getUsername();
		String verCode = verify_code.getVerify();
		try {
		String db_search = DBManager.getVerificationCode(username);
		if(verCode.equals(db_search)) {
			DBManager.Verify(username);
	        DBManager.updateVerificationAttempts(username);

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
