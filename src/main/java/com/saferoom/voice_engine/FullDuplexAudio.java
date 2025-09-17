package com.saferoom.voice_engine;

import java.util.Random;
import org.freedesktop.gstreamer.*;

public class FullDuplexAudio {

  // sadece bunları doldur (CLI'dan da alabiliyorum)
  private static String PEER_IP  = "192.168.1.29";
  private static int    PEER_PORT         = 7001;   // karşı tarafın dinlediği SRT portu
  private static int    MY_LISTENING_PORT = 7000;  // bu tarafın dinlediği SRT portu

  // makul varsayılanlar
  private static int DEFAULT_BITRATE   = 64000; // Opus
  private static int DEFAULT_FRAME_MS  = 20;    // 10/20/40
  private static int DEFAULT_LATENCYMS = 60;    // SRT + jitterbuffer

  public static void main(String[] args) {
    // args: [peer_ip] [peer_port] [my_listen_port]
    if (args != null && args.length >= 3) {
      try {
        PEER_IP           = args[0];
        PEER_PORT         = Integer.parseInt(args[1]);
        MY_LISTENING_PORT = Integer.parseInt(args[2]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid port numbers: " + e.getMessage());
        return;
      }
    }

    try {
      Gst.init("FullDuplex", args);
    } catch (Exception e) {
      System.err.println("Failed to initialize GStreamer: " + e.getMessage());
      return;
    }

    // === TX: mic -> opus -> rtp -> srt (caller) ===
    Element mic   = createElementSafely("autoaudiosrc", "mic");
    Element aconv = createElementSafely("audioconvert", "aconv");
    Element ares  = createElementSafely("audioresample", "ares");
    Element enc   = createElementSafely("opusenc", "opusenc");
    
    if (mic == null || aconv == null || ares == null || enc == null) {
      System.err.println("Failed to create TX audio elements");
      return;
    }

    try {
      enc.set("bitrate", DEFAULT_BITRATE);
      enc.set("inband-fec", true);
      enc.set("dtx", true);
      enc.set("frame-size", DEFAULT_FRAME_MS);
    } catch (Exception e) {
      System.err.println("Failed to configure opus encoder: " + e.getMessage());
      return;
    }

    Element pay   = createElementSafely("rtpopuspay", "rtpopuspay");
    if (pay == null) {
      System.err.println("Failed to create RTP payloader");
      return;
    }
    
    // SSRC rastgele; çakışma riskini azalt
    int mySsrc = new Random().nextInt() & 0x7fffffff;
    Element qtx   = createElementSafely("queue", "qtx");
    Element srtOut = createElementSafely("srtclientsink", "srtclientsink");
    
    if (qtx == null || srtOut == null) {
      System.err.println("Failed to create TX pipeline elements");
      return;
    }
    
    try {
      pay.set("pt", 96);
      pay.set("ssrc", mySsrc);  // rtpopuspay supports ssrc property
      // tek konfigurasyonla gönder: karşı tarafın dinlediği port
      srtOut.set("uri", "srt://" + PEER_IP + ":" + PEER_PORT + "?mode=caller&latency=" + DEFAULT_LATENCYMS);
    } catch (Exception e) {
      System.err.println("Failed to configure TX elements: " + e.getMessage());
      return;
    }

    // === RX: srt (listener) -> rtp -> jitterbuffer -> opus -> hoparlör ===
    Element srtIn  = createElementSafely("srtserversrc", "srtserversrc");
    if (srtIn == null) {
      System.err.println("Failed to create SRT server source");
      return;
    }
    
    try {
      srtIn.set("uri", "srt://0.0.0.0:" + MY_LISTENING_PORT + "?mode=listener");
    } catch (Exception e) {
      System.err.println("Failed to configure SRT server: " + e.getMessage());
      return;
    }

    Element depayStream = createElementSafely("rtpstreamdepay", "rtpstreamdepay");
    if (depayStream == null) {
      System.err.println("Failed to create RTP stream depayloader");
      return;
    }

    // SSRC sabitleme YOK: herhangi bir OPUS RTP'yi kabul et (pt=96 yeterli)
    // clock-rate/encoding-name/PT ver; ssrc alanını kaps içine koyma
    Caps caps = null;
    try {
      caps = Caps.fromString(
        "application/x-rtp,media=audio,clock-rate=48000,encoding-name=OPUS,payload=96"
      );
    } catch (Exception e) {
      System.err.println("Failed to create caps: " + e.getMessage());
      return;
    }
    
    Element capsFilter = createElementSafely("capsfilter", "caps");
    if (capsFilter == null) {
      System.err.println("Failed to create caps filter");
      return;
    }
    
    try {
      capsFilter.set("caps", caps);
    } catch (Exception e) {
      System.err.println("Failed to set caps: " + e.getMessage());
      return;
    }

    Element jitter = createElementSafely("rtpjitterbuffer", "rtpjitter");
    Element depay = createElementSafely("rtpopusdepay", "rtpopusdepay");
    Element dec   = createElementSafely("opusdec", "opusdec");
    Element arx1  = createElementSafely("audioconvert", "arx1");
    Element arx2  = createElementSafely("audioresample", "arx2");
    Element qrx   = createElementSafely("queue", "qrx");
    Element out   = createElementSafely("autoaudiosink", "sink");
    
    if (jitter == null || depay == null || dec == null || arx1 == null || arx2 == null || qrx == null || out == null) {
      System.err.println("Failed to create RX audio elements");
      return;
    }
    
    try {
      jitter.set("latency", DEFAULT_LATENCYMS);
      jitter.set("do-lost", true);
      out.set("sync", false);
    } catch (Exception e) {
      System.err.println("Failed to configure RX elements: " + e.getMessage());
      return;
    }

    // === PIPELINE ===
    Pipeline p = new Pipeline("opus-over-srt-full-duplex");
    p.addMany(
      // TX
      mic, aconv, ares, enc, pay, qtx, srtOut,
      // RX
      srtIn, depayStream, capsFilter, jitter, depay, dec, arx1, arx2, qrx, out
    );

    // linkler
    try {
      if (!Element.linkMany(mic, aconv, ares, enc, pay, qtx, srtOut)) {
        System.err.println("Failed to link TX elements");
        return;
      }
      if (!Element.linkMany(srtIn, depayStream, capsFilter, jitter, depay, dec, arx1, arx2, qrx, out)) {
        System.err.println("Failed to link RX elements");
        return;
      }
    } catch (Exception e) {
      System.err.println("Error linking elements: " + e.getMessage());
      return;
    }

    // bus log
    Bus bus = p.getBus();
    bus.connect((Bus.ERROR) (source, code, message) ->
      System.err.println("GST ERROR: " + message)
    );

    // adaptif kontrol
    Adaptive_Controller controller = new Adaptive_Controller(jitter, enc, p);
    controller.start();

    // çalıştır
    try {
      StateChangeReturn ret = p.play();
      if (ret == StateChangeReturn.FAILURE) {
        System.err.println("Failed to start pipeline");
        controller.shutdown();
        return;
      }
      
      System.out.println("FullDuplexAudio started successfully");
      Gst.main();
    } catch (Exception e) {
      System.err.println("Error running pipeline: " + e.getMessage());
    } finally {
      // durdur
      controller.shutdown();
      p.stop();
    }
  }
  
  private static Element createElementSafely(String factoryName, String elementName) {
    try {
      Element element = ElementFactory.make(factoryName, elementName);
      if (element == null) {
        System.err.println("Failed to create element: " + factoryName + " (" + elementName + ")");
      }
      return element;
    } catch (Exception e) {
      System.err.println("Exception creating element " + factoryName + ": " + e.getMessage());
      return null;
    }
  }
}
