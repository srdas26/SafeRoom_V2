package com.saferoom.voice_engine;

import java.util.Random;
import org.freedesktop.gstreamer.*;

public class SafeSRTAudio {

  // Configuration
  private static String PEER_IP  = "192.168.1.29";
  private static int    PEER_PORT         = 7000;   // karşı tarafın dinlediği SRT portu
  private static int    MY_LISTENING_PORT = 7001;   // bu tarafın dinlediği SRT portu

  // Audio settings - very conservative
  private static int DEFAULT_BITRATE   = 24000; // Lower bitrate
  private static int DEFAULT_FRAME_MS  = 40;    // Larger frames for stability
  private static int DEFAULT_LATENCYMS = 200;   // Higher latency for stability

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
      System.out.println("Initializing GStreamer for Safe SRT...");
      Gst.init("SafeSRTAudio", new String[]{"--gst-debug-level=1"}); // Lower debug level
      Thread.sleep(2000); // Longer wait for initialization
      
      System.out.println("GStreamer initialized successfully");
    } catch (Exception e) {
      System.err.println("Failed to initialize GStreamer: " + e.getMessage());
      return;
    }

    Pipeline pipeline = null;
    try {
      // === CREATE PIPELINE ===
      pipeline = new Pipeline("safe-srt-audio");
      
      // === TX: Simple audio test source -> opus -> rtp -> srt ===
      System.out.println("Creating TX elements...");
      
      // Use test source instead of real mic to avoid PulseAudio issues
      Element audioSrc = createElementSafely("audiotestsrc", "testsrc");
      if (audioSrc == null) {
        System.err.println("Failed to create audio test source");
        return;
      }
      
      Element audioConvert1 = createElementSafely("audioconvert", "aconv1");
      Element audioResample1 = createElementSafely("audioresample", "ares1");
      Element opusEnc = createElementSafely("opusenc", "opusenc");
      Element rtpOpusPay = createElementSafely("rtpopuspay", "rtpopuspay");
      Element queue1 = createElementSafely("queue", "queue1");
      
      if (audioConvert1 == null || audioResample1 == null || 
          opusEnc == null || rtpOpusPay == null || queue1 == null) {
        System.err.println("Failed to create basic TX elements");
        return;
      }
      
      // Configure basic elements first
      try {
        audioSrc.set("wave", 0);  // sine wave
        audioSrc.set("freq", 440); // 440Hz tone
        audioSrc.set("volume", 0.1); // Low volume
        
        opusEnc.set("bitrate", DEFAULT_BITRATE);
        opusEnc.set("frame-size", DEFAULT_FRAME_MS);
        opusEnc.set("complexity", 1); // Lowest complexity
        opusEnc.set("inband-fec", false); // Disable FEC initially
        opusEnc.set("dtx", false); // Disable DTX
        
        rtpOpusPay.set("pt", 96);
        
        System.out.println("Basic TX elements configured");
      } catch (Exception e) {
        System.err.println("Failed to configure basic TX elements: " + e.getMessage());
        return;
      }
      
      // Create SRT sink with extreme caution
      System.out.println("Creating SRT sink with minimal configuration...");
      Element srtSink = null;
      try {
        srtSink = ElementFactory.make("srtsink", "srtsink"); // Use srtsink instead of srtclientsink
        if (srtSink == null) {
          System.err.println("Failed to create srtsink, trying srtclientsink...");
          srtSink = ElementFactory.make("srtclientsink", "srtclientsink");
        }
        
        if (srtSink == null) {
          System.err.println("No SRT sink available, falling back to UDP");
          // Fallback to UDP
          srtSink = createElementSafely("udpsink", "udpsink");
          if (srtSink != null) {
            srtSink.set("host", PEER_IP);
            srtSink.set("port", PEER_PORT);
            srtSink.set("sync", false);
          }
        } else {
          System.out.println("SRT sink created successfully");
          // Minimal SRT configuration to avoid crashes
          try {
            String srtUri = "srt://" + PEER_IP + ":" + PEER_PORT;
            srtSink.set("uri", srtUri);
            System.out.println("SRT URI set: " + srtUri);
          } catch (Exception e) {
            System.err.println("Failed to set SRT URI, using basic properties: " + e.getMessage());
            // Try alternative approach
            try {
              srtSink.set("localaddress", "0.0.0.0");
              srtSink.set("localport", 0);
              srtSink.set("peeraddress", PEER_IP);
              srtSink.set("peerport", PEER_PORT);
            } catch (Exception e2) {
              System.err.println("Failed alternative SRT config: " + e2.getMessage());
              return;
            }
          }
        }
      } catch (Exception e) {
        System.err.println("Exception creating SRT sink: " + e.getMessage());
        return;
      }
      
      if (srtSink == null) {
        System.err.println("Failed to create any suitable sink");
        return;
      }
      
      // === RX: Minimal receive path ===
      System.out.println("Creating minimal RX elements...");
      
      Element srtSrc = null;
      try {
        srtSrc = ElementFactory.make("srtsrc", "srtsrc");
        if (srtSrc == null) {
          srtSrc = ElementFactory.make("srtserversrc", "srtserversrc");
        }
        
        if (srtSrc == null) {
          System.err.println("No SRT source available, using UDP");
          srtSrc = createElementSafely("udpsrc", "udpsrc");
          if (srtSrc != null) {
            srtSrc.set("port", MY_LISTENING_PORT);
            srtSrc.set("caps", Caps.fromString("application/x-rtp"));
          }
        } else {
          System.out.println("SRT source created successfully");
          try {
            String srtUri = "srt://0.0.0.0:" + MY_LISTENING_PORT;
            srtSrc.set("uri", srtUri);
            System.out.println("SRT source URI set: " + srtUri);
          } catch (Exception e) {
            System.err.println("Failed to set SRT source URI: " + e.getMessage());
            return;
          }
        }
      } catch (Exception e) {
        System.err.println("Exception creating SRT source: " + e.getMessage());
        return;
      }
      
      Element rtpOpusDepay = createElementSafely("rtpopusdepay", "rtpopusdepay");
      Element opusDec = createElementSafely("opusdec", "opusdec");
      Element audioConvert2 = createElementSafely("audioconvert", "aconv2");
      Element fakeSink = createElementSafely("fakesink", "fakesink"); // Use fakesink to avoid audio output issues
      
      if (srtSrc == null || rtpOpusDepay == null || opusDec == null || 
          audioConvert2 == null || fakeSink == null) {
        System.err.println("Failed to create RX elements");
        return;
      }
      
      // Configure RX elements
      try {
        opusDec.set("use-inband-fec", false);
        opusDec.set("plc", false);
        fakeSink.set("dump", false); // Don't dump data to console
        fakeSink.set("sync", false);
        
        System.out.println("RX elements configured");
      } catch (Exception e) {
        System.err.println("Failed to configure RX elements: " + e.getMessage());
        return;
      }
      
      // === ADD TO PIPELINE ===
      System.out.println("Adding elements to pipeline...");
      try {
        pipeline.addMany(
          // TX
          audioSrc, audioConvert1, audioResample1, opusEnc, rtpOpusPay, queue1, srtSink,
          // RX  
          srtSrc, rtpOpusDepay, opusDec, audioConvert2, fakeSink
        );
        System.out.println("Elements added to pipeline");
      } catch (Exception e) {
        System.err.println("Failed to add elements to pipeline: " + e.getMessage());
        return;
      }
      
      // === LINK ELEMENTS ===
      System.out.println("Linking elements...");
      try {
        if (!Element.linkMany(audioSrc, audioConvert1, audioResample1, opusEnc, rtpOpusPay, queue1, srtSink)) {
          System.err.println("Failed to link TX elements");
          return;
        }
        System.out.println("TX elements linked");
        
        if (!Element.linkMany(srtSrc, rtpOpusDepay, opusDec, audioConvert2, fakeSink)) {
          System.err.println("Failed to link RX elements");
          return;
        }
        System.out.println("RX elements linked");
        
      } catch (Exception e) {
        System.err.println("Error linking elements: " + e.getMessage());
        return;
      }
      
      // === BUS HANDLING ===
      Bus bus = pipeline.getBus();
      bus.connect((Bus.ERROR) (source, code, message) -> {
        System.err.println("GST ERROR from " + source.getName() + ": " + message);
        // Don't quit immediately on errors
      });
      
      // === START PIPELINE ===
      System.out.println("Starting Safe SRT Audio pipeline...");
      System.out.println("Listening on SRT port: " + MY_LISTENING_PORT);
      System.out.println("Sending to: " + PEER_IP + ":" + PEER_PORT);
      
      final Pipeline finalPipeline = pipeline;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("\nShutting down gracefully...");
        try {
          finalPipeline.setState(State.NULL);
        } catch (Exception e) {
          System.err.println("Error during shutdown: " + e.getMessage());
        }
      }));
      
      // Very careful state transitions
      System.out.println("Setting to READY state...");
      StateChangeReturn ret = pipeline.setState(State.READY);
      if (ret == StateChangeReturn.FAILURE) {
        System.err.println("Failed to set pipeline to READY");
        return;
      }
      
      Thread.sleep(1000); // Wait for state change
      
      System.out.println("Setting to PLAYING state...");
      ret = pipeline.setState(State.PLAYING);
      if (ret == StateChangeReturn.FAILURE) {
        System.err.println("Failed to start pipeline");
        return;
      }
      
      System.out.println("Safe SRT Audio started successfully");
      System.out.println("Press Ctrl+C to stop...");
      
      Gst.main();
      
    } catch (Exception e) {
      System.err.println("Error running pipeline: " + e.getMessage());
      e.printStackTrace();
    } finally {
      try {
        if (pipeline != null) {
          pipeline.setState(State.NULL);
        }
      } catch (Exception e) {
        System.err.println("Error stopping pipeline: " + e.getMessage());
      }
    }
  }
  
  private static Element createElementSafely(String factoryName, String elementName) {
    try {
      System.out.println("Creating element: " + factoryName + " as " + elementName);
      
      Element element = ElementFactory.make(factoryName, elementName);
      if (element == null) {
        System.err.println("Failed to create element: " + factoryName + " (" + elementName + ")");
        return null;
      }
      
      System.out.println("Successfully created: " + factoryName + " as " + elementName);
      return element;
    } catch (Exception e) {
      System.err.println("Exception creating element " + factoryName + ": " + e.getMessage());
      return null;
    }
  }
}
