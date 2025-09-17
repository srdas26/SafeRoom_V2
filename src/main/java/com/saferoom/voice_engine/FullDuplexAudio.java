package com.saferoom.voice_engine;

import java.util.Random;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.*;

public class FullDuplexAudio {

  // sadece bunları doldur (CLI'dan da alabiliyorum)
  private static String PEER_IP           = "127.0.0.1";
  private static int    PEER_PORT         = 9000;   // karşı tarafın dinlediği SRT portu
  private static int    MY_LISTENING_PORT = 10000;  // bu tarafın dinlediği SRT portu

  // makul varsayılanlar
  private static int DEFAULT_BITRATE   = 64000; // Opus
  private static int DEFAULT_FRAME_MS  = 20;    // 10/20/40
  private static int DEFAULT_LATENCYMS = 60;    // SRT + jitterbuffer

  public static void main(String[] args) {
    // args: [peer_ip] [peer_port] [my_listen_port]
    if (args != null && args.length >= 3) {
      PEER_IP           = args[0];
      PEER_PORT         = Integer.parseInt(args[1]);
      MY_LISTENING_PORT = Integer.parseInt(args[2]);
    }

    Gst.init("FullDuplex", args);

    // === TX: mic -> opus -> rtp -> srt (caller) ===
    Element mic   = ElementFactory.make("autoaudiosrc", "mic");
    Element aconv = ElementFactory.make("audioconvert", "aconv");
    Element ares  = ElementFactory.make("audioresample", "ares");
    Element enc   = ElementFactory.make("opusenc", "opusenc");

    enc.set("bitrate", DEFAULT_BITRATE);
    enc.set("inband-fec", true);
    enc.set("dtx", true);
    enc.set("frame-size", DEFAULT_FRAME_MS);

    Element pay   = ElementFactory.make("rtpopuspay", "rtpopuspay");
    pay.set("pt", 96);

    // SSRC rastgele; çakışma riskini azalt
    int mySsrc = new Random().nextInt() & 0x7fffffff;
    Element rtpPay = ElementFactory.make("rtpstreampay", "rtpstreampay");
    rtpPay.set("ssrc", mySsrc);

    Element qtx   = ElementFactory.make("queue", "qtx");

    Element srtOut = ElementFactory.make("srtclientsink", "srtclientsink");
    // tek konfigurasyonla gönder: karşı tarafın dinlediği port
    srtOut.set("uri", "srt://" + PEER_IP + ":" + PEER_PORT + "?mode=caller&latency=" + DEFAULT_LATENCYMS);

    // === RX: srt (listener) -> rtp -> jitterbuffer -> opus -> hoparlör ===
    Element srtIn  = ElementFactory.make("srtserversrc", "srtserversrc");
    srtIn.set("uri", "srt://0.0.0.0:" + MY_LISTENING_PORT + "?mode=listener");

    Element depayStream = ElementFactory.make("rtpstreamdepay", "rtpstreamdepay");

    // SSRC sabitleme YOK: herhangi bir OPUS RTP'yi kabul et (pt=96 yeterli)
    // clock-rate/encoding-name/PT ver; ssrc alanını kaps içine koyma
    Caps caps = Caps.fromString(
      "application/x-rtp,media=audio,clock-rate=48000,encoding-name=OPUS,payload=96"
    );
    Element capsFilter = ElementFactory.make("capsfilter", "caps");
    capsFilter.set("caps", caps);

    Element jitter = ElementFactory.make("rtpjitterbuffer", "rtpjitter");
    jitter.set("latency", DEFAULT_LATENCYMS);
    jitter.set("do-lost", true);

    Element depay = ElementFactory.make("rtpopusdepay", "rtpopusdepay");
    Element dec   = ElementFactory.make("opusdec", "opusdec");
    Element arx1  = ElementFactory.make("audioconvert", "arx1");
    Element arx2  = ElementFactory.make("audioresample", "arx2");
    Element qrx   = ElementFactory.make("queue", "qrx");
    Element out   = ElementFactory.make("autoaudiosink", "sink");
    out.set("sync", false);

    // === PIPELINE ===
    Pipeline p = new Pipeline("opus-over-srt-full-duplex");
    p.addMany(
      // TX
      mic, aconv, ares, enc, pay, rtpPay, qtx, srtOut,
      // RX
      srtIn, depayStream, capsFilter, jitter, depay, dec, arx1, arx2, qrx, out
    );

    // linkler
    Element.linkMany(mic, aconv, ares, enc, pay, rtpPay, qtx, srtOut);
    Element.linkMany(srtIn, depayStream, capsFilter, jitter, depay, dec, arx1, arx2, qrx, out);

    // bus log
    Bus bus = p.getBus();
    bus.connect((Bus.ERROR) (source, code, message) ->
      System.err.println("GST ERROR: " + message)
    );

    // adaptif kontrol
    Adaptive_Controller controller = new Adaptive_Controller(jitter, enc, p);
    controller.start();

    // çalıştı
    p.play();
    Gst.main();

    // durdur
    controller.shutdown();
    p.stop();
  }
}
