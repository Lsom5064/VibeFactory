import 'dart:convert';
import 'dart:io';

class VibeDataRecord {
  const VibeDataRecord({
    required this.recordId,
    required this.taskId,
    required this.packageName,
    required this.collection,
    required this.ownerId,
    required this.data,
    required this.createdAt,
    required this.updatedAt,
    this.deletedAt,
  });

  final String recordId;
  final String taskId;
  final String packageName;
  final String collection;
  final String ownerId;
  final Map<String, dynamic> data;
  final String createdAt;
  final String updatedAt;
  final String? deletedAt;

  factory VibeDataRecord.fromJson(Map<String, dynamic> json) {
    final rawData = json['data'];
    return VibeDataRecord(
      recordId: (json['record_id'] ?? '').toString(),
      taskId: (json['task_id'] ?? '').toString(),
      packageName: (json['package_name'] ?? '').toString(),
      collection: (json['collection'] ?? '').toString(),
      ownerId: (json['owner_id'] ?? '').toString(),
      data: rawData is Map<String, dynamic>
          ? rawData
          : rawData is Map
              ? Map<String, dynamic>.from(rawData)
              : <String, dynamic>{},
      createdAt: (json['created_at'] ?? '').toString(),
      updatedAt: (json['updated_at'] ?? '').toString(),
      deletedAt: json['deleted_at']?.toString(),
    );
  }
}

class VibeDataClient {
  static const Duration _requestTimeout = Duration(seconds: 30);

  VibeDataClient({
    required this.serverBaseUrl,
    required this.taskId,
    required this.packageName,
    this.ownerId = '',
    HttpClient? httpClient,
  }) : _httpClient = httpClient ??
            (HttpClient()
              ..connectionTimeout = const Duration(seconds: 15));

  final String serverBaseUrl;
  final String taskId;
  final String packageName;
  final String ownerId;
  final HttpClient _httpClient;

  void close({bool force = false}) {
    _httpClient.close(force: force);
  }

  Uri _collectionUri(String collection, [String? recordId, Map<String, String>? query]) {
    final base = serverBaseUrl.endsWith('/')
        ? serverBaseUrl.substring(0, serverBaseUrl.length - 1)
        : serverBaseUrl;
    final encodedCollection = Uri.encodeComponent(collection);
    final encodedRecordId = recordId == null ? '' : '/${Uri.encodeComponent(recordId)}';
    final uri = Uri.parse('$base/apps/$taskId/data/$encodedCollection$encodedRecordId');
    return uri.replace(queryParameters: query);
  }

  Future<List<VibeDataRecord>> list(
    String collection, {
    String? ownerId,
    int limit = 100,
  }) async {
    final body = await _send(
      'GET',
      _collectionUri(collection, null, {
        'package_name': packageName,
        if ((ownerId ?? this.ownerId).isNotEmpty) 'owner_id': ownerId ?? this.ownerId,
        'limit': limit.toString(),
      }),
    );
    final records = body['records'];
    if (records is! List) {
      return const [];
    }
    return records
        .whereType<Map>()
        .map((item) => VibeDataRecord.fromJson(Map<String, dynamic>.from(item)))
        .toList(growable: false);
  }

  Future<VibeDataRecord> create(
    String collection,
    Map<String, dynamic> data, {
    String? ownerId,
  }) async {
    final body = await _send(
      'POST',
      _collectionUri(collection),
      payload: {
        'package_name': packageName,
        'owner_id': ownerId ?? this.ownerId,
        'data': data,
      },
    );
    return VibeDataRecord.fromJson(Map<String, dynamic>.from(body['record'] as Map));
  }

  Future<VibeDataRecord> get(String collection, String recordId) async {
    final body = await _send(
      'GET',
      _collectionUri(collection, recordId, {'package_name': packageName}),
    );
    return VibeDataRecord.fromJson(Map<String, dynamic>.from(body['record'] as Map));
  }

  Future<VibeDataRecord> update(
    String collection,
    String recordId,
    Map<String, dynamic> data, {
    String? ownerId,
    bool replace = false,
  }) async {
    final body = await _send(
      'PATCH',
      _collectionUri(collection, recordId),
      payload: {
        'package_name': packageName,
        'owner_id': ownerId ?? this.ownerId,
        'data': data,
        'replace': replace,
      },
    );
    return VibeDataRecord.fromJson(Map<String, dynamic>.from(body['record'] as Map));
  }

  Future<void> delete(String collection, String recordId) async {
    await _send(
      'DELETE',
      _collectionUri(collection, recordId, {'package_name': packageName}),
    );
  }

  Future<Map<String, dynamic>> _send(
    String method,
    Uri uri, {
    Map<String, dynamic>? payload,
  }) async {
    final request = await _httpClient.openUrl(method, uri).timeout(_requestTimeout);
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    if (payload != null) {
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode(payload));
    }
    final response = await request.close().timeout(_requestTimeout);
    final responseText = await response.transform(utf8.decoder).join().timeout(_requestTimeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HttpException(
        'VibeData request failed: ${response.statusCode} $responseText',
        uri: uri,
      );
    }
    final decoded = jsonDecode(responseText);
    if (decoded is Map<String, dynamic>) {
      return decoded;
    }
    if (decoded is Map) {
      return Map<String, dynamic>.from(decoded);
    }
    return <String, dynamic>{};
  }
}
