/// Bounded, coordinate-free timeline of what the engine decided. Coordinates
/// never enter it: it is meant to be copied out of a device and shared.
class TrackingLog {
  TrackingLog({this.capacity = 600});

  final int capacity;
  final _entries = <(DateTime, String)>[];
  int _dropped = 0;

  void note(DateTime at, String text) {
    if (_entries.length >= capacity) {
      _entries.removeAt(0);
      _dropped++;
    }
    _entries.add((at, text));
  }

  void clear() {
    _entries.clear();
    _dropped = 0;
  }

  int get length => _entries.length;

  String report() {
    final b = StringBuffer()
      ..writeln(
        'TIMELINE: ${_entries.length} entries'
        '${_dropped > 0 ? ' (+$_dropped older dropped)' : ''}',
      );
    for (final e in _entries) {
      final t = e.$1;
      String two(int v) => v.toString().padLeft(2, '0');
      b.writeln('${two(t.hour)}:${two(t.minute)}:${two(t.second)} ${e.$2}');
    }
    return b.toString();
  }
}
