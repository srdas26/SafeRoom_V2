# 🔐 SafeRoom_V2 — The Decentralized Workspace.
> “Zoom, Teams, Discord?  
> They rent you a room.  
> **We give you the building.**”  
> — SafeRoom_V2

---

## 🚀 Beyond "Just Meetings"
SafeRoom_V2 is not just a video calling app. It is a **serverless, secure, all-in-one collaboration protocol** that challenges the giants:

|    Competing With | Why SafeRoom Wins |
|-------------------|-------------------|
| **Zoom / Teams** | No central servers recording your calls. Low latency via **Dynamic Tree Routing**. |
| **Discord** | "Serverless Rooms" — Your community lives on your devices, not in a data center. No bans, no data mining. |
| **WeTransfer** | **BitTorrent-like P2P File Transfer**. Send GBs or TBs directly. No upload limits, no cloud storage costs. |
| **TeamViewer** | Secure, P2P remote screen control without the corporate price tag or security backdoors. |

---

## ⚡ Architecture: True Serverless P2P

Unlike traditional apps that relay everything through a central cloud, SafeRoom_V2 uses a **completely decentralized architecture**:

### 1. Dynamic Tree Algorithm (Mesh Routing)
Instead of a star topology (everyone connects to a server), SafeRoom builds a **dynamic, self-healing mesh**.
- **Low Latency:** Packets take the shortest path between peers.
- **Resilience:** If one node drops, the tree rebuilds instantly.
- **Scalability:** The network grows stronger as more users join.

### 2. BitTorrent-like File Vault
Forget uploading to the cloud. SafeRoom uses a swarm-like protocol for file transfers.
- **Zero Cloud Storage:** Files stream directly from sender to receiver(s).
- **Parallel Chunks:** Large files are split and sent via multiple paths for maximum speed.
- **Resume Capability:** Network drop? It picks up exactly where it left off.

### 3. Serverless Rooms
In SafeRoom, a "Room" isn't a database entry on our server. It's a **cryptographic space** held together by the participants.
- **Ephemeral or Persistent:** You decide.
- **No Central Admin:** The community owns the infrastructure.
- **Uncensorable:** No central authority can delete your room or read your chats.

---

## 🛡️ Security vs. "Cloud Security"

| Feature | Traditional Cloud Apps (Zoom/Teams/Discord) | 🚀 SafeRoom_V2 |
|---------|---------------------------------------------|----------------|
| **Data Flow** | Client ➡ Server ➡ Client | Client ➡ Client (P2P) |
| **Encryption** | Decrypted at server for processing | **True E2EE** (X25519+HKDF). Server sees nothing. |
| **File Limits** | Capped (2GB - 15GB) | **Unlimited** (Disk speed is the limit) |
| **Privacy** | Metadata mined for ads/training AI | **Zero Knowledge**. We don't know who you are. |
| **Trust Model** | Trust the Corporation | **Trust the Math** |

---

## 🧠 Philosophy: You Own The Pipe

> **If your data passes through their server, it is their data.**

SafeRoom_V2 eliminates the middleman entirely.
- We don't see your files.
- We don't hear your calls.
- We don't host your rooms.

We simply provide the **mathematically secure protocol** for you to connect directly.

---

## 🚧 Status
**Active Development.**
Moving fast to replace your entire collaboration stack with a single, secure, native Java executable.
