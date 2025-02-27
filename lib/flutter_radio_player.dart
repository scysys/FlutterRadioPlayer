import 'dart:async';

import 'package:flutter/services.dart';

class FlutterRadioPlayer {
  static const MethodChannel _channel =
      const MethodChannel('flutter_radio_player');

  static const EventChannel _eventChannel =
      const EventChannel("flutter_radio_player_stream");

  static const EventChannel _eventChannelMetaData =
      const EventChannel("flutter_radio_player_meta_stream");

  // constants to support event channel
  static const flutter_radio_stopped = "flutter_radio_stopped";
  static const flutter_radio_playing = "flutter_radio_playing";
  static const flutter_radio_paused = "flutter_radio_paused";
  static const flutter_radio_error = "flutter_radio_error";
  static const flutter_radio_loading = "flutter_radio_loading";

  static Stream<String>? _isPlayingStream;
  static Stream<String>? _metaDataStream;

  Future<void> init(String initialTitle, String subTitle, String streamURL,
      bool playWhenReady) async {
    return await _channel.invokeMethod("initService", {
      "initialTitle": initialTitle,
      "subTitle": subTitle,
      "streamURL": streamURL,
      "playWhenReady": playWhenReady ? "true" : "false"
    });
  }

  Future<bool> play() async {
    return await _channel.invokeMethod("play") ?? false;
  }

  Future<bool> pause() async {
    return await _channel.invokeMethod("pause") ?? false;
  }

  Future<bool> playOrPause() async {
    print("Invoking platform method: playOrPause");
    return await _channel.invokeMethod("playOrPause") ?? false;
  }

  Future<bool> stop() async {
    return await _channel.invokeMethod("stop") ?? false;
  }

  Future<bool> isPlaying() async {
    bool isPlaying = await _channel.invokeMethod("isPlaying");
    return isPlaying;
  }

  Future<void> setVolume(double volume) async {
    await _channel.invokeMethod("setVolume", {"volume": volume});
  }

  Future<void> setUrl(String streamUrl, String playWhenReady) async {
    await _channel.invokeMethod(
        "setUrl", {"playWhenReady": playWhenReady, "streamUrl": streamUrl});
  }

  Future<String> currentSongTitle() async {
    return await _channel.invokeMethod("currentSongTitle");
  }

  /// Get the player stream.
  Stream<String>? get isPlayingStream {
    if (_isPlayingStream == null) {
      _isPlayingStream =
          _eventChannel.receiveBroadcastStream().map<String>((value) => value);
    }
    return _isPlayingStream;
  }

  Stream<String>? get metaDataStream {
    if (_metaDataStream == null) {
      _metaDataStream = _eventChannelMetaData
          .receiveBroadcastStream()
          .map<String>((value) => value);
    }

    return _metaDataStream;
  }
}

/// Flutter_radio_playback status
enum PlaybackStatus {
  flutter_radio_stopped,
  flutter_radio_playing,
  flutter_radio_paused,
  flutter_radio_error,
  flutter_radio_loading,
}
