// Bench app for record_video_data_sync: landscape preview, Record / Stop.
// Each recording is saved with a JSON sidecar holding the exact phone time
// of its first frame, in the app's external files folder
// (/sdcard/Android/data/ca.max2000.record_video_data_sync_example/files/),
// so a PC can pull both and line the video up with logged LED flashes.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record_video_data_sync/record_video_data_sync.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft]);
  runApp(const MaterialApp(home: BenchPage(), debugShowCheckedModeBanner: false));
}

class BenchPage extends StatefulWidget {
  const BenchPage({super.key});
  @override
  State<BenchPage> createState() => _BenchPageState();
}

class _BenchPageState extends State<BenchPage> {
  PreciseRecorder? _rec;
  bool _recording = false;
  String _status = 'Opening camera...';
  int _count = 0;

  @override
  void initState() {
    super.initState();
    _open();
  }

  Future<void> _open() async {
    final ok = await Permission.camera.request();
    if (!ok.isGranted) {
      setState(() => _status = 'Camera permission denied');
      return;
    }
    try {
      final r = await PreciseRecorder.open(width: 1280, height: 720, fps: 30);
      setState(() {
        _rec = r;
        _status = 'Ready (${r.width}x${r.height}, sensor ${r.sensorOrientation} deg)';
      });
    } catch (e) {
      setState(() => _status = 'Open failed: $e');
    }
  }

  Future<void> _toggle() async {
    final r = _rec;
    if (r == null) return;
    if (!_recording) {
      final dir = await getExternalStorageDirectory();
      _count++;
      final path = '${dir!.path}/clip_${DateTime.now().millisecondsSinceEpoch}.mp4';
      final pressedUs = DateTime.now().microsecondsSinceEpoch;
      await r.start(path, rotationDegrees: 90);
      setState(() {
        _recording = true;
        _status = 'Recording $_count...';
      });
      _pressedUs = pressedUs;
    } else {
      final res = await r.stop();
      setState(() => _recording = false);
      if (res == null) {
        setState(() => _status = 'No frames recorded');
        return;
      }
      final sidecar = {
        'path': res.path,
        'first_frame_epoch_us': res.firstFrameEpochUs,
        'last_frame_epoch_us': res.lastFrameEpochUs,
        'frame_count': res.frameCount,
        'timestamp_source': res.timestampSource,
        'width': res.width,
        'height': res.height,
        'fps': res.fps,
        'record_pressed_epoch_us': _pressedUs,
      };
      await File(res.path.replaceAll('.mp4', '.json')).writeAsString(jsonEncode(sidecar));
      final startMs = (res.firstFrameEpochUs - (_pressedUs ?? res.firstFrameEpochUs)) / 1000.0;
      setState(() => _status =
          'Saved: ${res.frameCount} frames, first frame ${startMs.toStringAsFixed(1)} ms after Record (${res.timestampSource})');
    }
  }

  int? _pressedUs;

  @override
  void dispose() {
    _rec?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final r = _rec;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(children: [
        if (r != null) Center(child: r.preview()),
        Positioned(
          left: 16, top: 16, right: 140,
          child: Text(_status, style: const TextStyle(color: Colors.white, fontSize: 16)),
        ),
        Positioned(
          right: 24, bottom: 24,
          child: FloatingActionButton.large(
            backgroundColor: _recording ? Colors.white : Colors.red,
            onPressed: r == null ? null : _toggle,
            child: Icon(_recording ? Icons.stop : Icons.fiber_manual_record,
                color: _recording ? Colors.red : Colors.white),
          ),
        ),
      ]),
    );
  }
}
