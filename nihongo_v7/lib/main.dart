import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(scaffoldBackgroundColor: Colors.black),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {

  static const platform = MethodChannel('overlay_channel');
  static const whisperChannel = MethodChannel('whisper_channel');

  String japaneseText = "";
  String englishText = "Press START — then open any Japanese video";
  bool isRunning = false;
  bool hasOverlay = false;
  String statusMsg = "";

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    whisperChannel.setMethodCallHandler((call) async {
      if (call.method == "onTranscription") {
        final args = call.arguments;
        if (args is Map) {
          if (mounted) setState(() {
            japaneseText = args["japanese"]?.toString() ?? "";
            englishText = args["english"]?.toString() ?? englishText;
          });
        }
      }
    });
    _checkOverlay();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkOverlay();
  }

  Future<void> _checkOverlay() async {
    try {
      final ok = await platform.invokeMethod<bool>('hasOverlayPermission') ?? false;
      if (mounted) setState(() => hasOverlay = ok);
    } catch (_) {}
  }

  Future<void> _start() async {
    try {
      // Start overlay
      final overlayOk = await platform.invokeMethod<bool>('startOverlay') ?? false;
      if (!overlayOk) {
        setState(() => statusMsg = '⚠️ Allow "Display over other apps" → come back → tap START again');
        return;
      }
      // Request screen capture (triggers system dialog)
      await platform.invokeMethod('startInternalAudioCapture');
      setState(() { isRunning = true; statusMsg = '✅ Active! Open any Japanese video now.'; });
    } catch (e) {
      setState(() => statusMsg = 'Error: $e');
    }
  }

  Future<void> _stop() async {
    try {
      await platform.invokeMethod('stopCapture');
      setState(() {
        isRunning = false;
        englishText = "Press START — then open any Japanese video";
        japaneseText = "";
        statusMsg = "";
      });
    } catch (_) {}
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(children: [
                const Text('🎌', style: TextStyle(fontSize: 30)),
                const SizedBox(width: 10),
                const Expanded(child: Text('Nihongo Lens',
                    style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold))),
                if (isRunning) Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(color: Colors.red, borderRadius: BorderRadius.circular(12)),
                  child: const Text('LIVE', style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
                ),
              ]),
              const SizedBox(height: 4),
              const Text('Japanese → English Live Captions',
                  style: TextStyle(color: Colors.white38, fontSize: 13)),

              const SizedBox(height: 24),

              // Overlay permission row
              _permRow('Display over apps', hasOverlay, () async {
                await platform.invokeMethod('startOverlay');
                await Future.delayed(const Duration(seconds: 1));
                _checkOverlay();
              }),

              const SizedBox(height: 32),

              // Translation display
              if (japaneseText.isNotEmpty) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.white10, borderRadius: BorderRadius.circular(8)),
                  child: Text(japaneseText,
                      style: const TextStyle(color: Colors.white60, fontSize: 15, letterSpacing: 1)),
                ),
                const SizedBox(height: 10),
              ],
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF0d1a0d),
                  borderRadius: BorderRadius.circular(12),
                  border: const Border(left: BorderSide(color: Colors.greenAccent, width: 4)),
                ),
                child: Text(englishText,
                    style: const TextStyle(color: Colors.greenAccent, fontSize: 22,
                        fontWeight: FontWeight.bold, height: 1.4)),
              ),

              if (statusMsg.isNotEmpty) ...[
                const SizedBox(height: 16),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.orange.withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.orange.withOpacity(0.4)),
                  ),
                  child: Text(statusMsg, style: const TextStyle(color: Colors.orange, fontSize: 13)),
                ),
              ],

              const Spacer(),

              // How it works
              if (!isRunning) Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0xFF111111), borderRadius: BorderRadius.circular(10)),
                child: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('How it works', style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
                  SizedBox(height: 8),
                  Text('1. Tap START → allow screen capture\n2. Minimize this app\n3. Open YouTube / any Japanese video\n4. Floating English captions appear over the video\n5. Drag the caption box anywhere',
                      style: TextStyle(color: Colors.white38, fontSize: 12, height: 1.6)),
                ]),
              ),

              const SizedBox(height: 16),

              // START / STOP
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: isRunning ? _stop : _start,
                  icon: Icon(isRunning ? Icons.stop : Icons.play_arrow),
                  label: Text(isRunning ? 'STOP CAPTIONS' : 'START LIVE TRANSLATION',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: isRunning ? Colors.grey[800] : const Color(0xFFFF3B3B),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _permRow(String label, bool granted, VoidCallback onTap) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF111111), borderRadius: BorderRadius.circular(10),
        border: Border.all(color: granted ? Colors.greenAccent.withOpacity(0.4) : Colors.white12),
      ),
      child: Row(children: [
        Icon(granted ? Icons.check_circle : Icons.radio_button_unchecked,
            color: granted ? Colors.greenAccent : Colors.white30, size: 22),
        const SizedBox(width: 12),
        Expanded(child: Text(label, style: const TextStyle(color: Colors.white, fontSize: 14))),
        if (!granted) GestureDetector(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(color: const Color(0xFFFF3B3B), borderRadius: BorderRadius.circular(6)),
            child: const Text('Allow', style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
          ),
        ),
      ]),
    );
  }
}
