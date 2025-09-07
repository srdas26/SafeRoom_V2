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

		public static int Login(String username, String Password)
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
					return 0;
				case 1:
					if(message.equals("N_REGISTER")){
						System.out.println("Not Registered");
						return 1;
					}else if(message.equals("WRONG_PASSWORD")){
						System.out.println("Wrong Password");
						return 3;
						}else{
							System.out.println("Blocked User");
							return 2;
						}
			default:
					System.out.println("Message has broken");
					return 4;					
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

	}

