import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:msal_flutter/msal_flutter.dart';

void main() {
  runApp(const B2CMsalApp());
}

class B2CMsalApp extends StatelessWidget {
  const B2CMsalApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'B2C MSAL Demo',
      theme: ThemeData(useMaterial3: true),
      home: const B2CLoginPage(),
    );
  }
}

class GuestProfile {
  const GuestProfile({
    required this.guestId,
    required this.email,
    required this.firstName,
    required this.lastName,
  });

  final String guestId;
  final String email;
  final String firstName;
  final String lastName;

  factory GuestProfile.fromJson(Map<String, dynamic> json) => GuestProfile(
    guestId: (json['guestId'] as String?) ?? '',
    email: (json['email'] as String?) ?? '',
    firstName: (json['firstName'] as String?) ?? '',
    lastName: (json['lastName'] as String?) ?? '',
  );
}

class B2CLoginPage extends StatefulWidget {
  const B2CLoginPage({super.key});

  @override
  State<B2CLoginPage> createState() => _B2CLoginPageState();
}

class _B2CLoginPageState extends State<B2CLoginPage> {
  // All environment-specific values are supplied at build/run time via
  // --dart-define-from-file=env/env.json (see env/env.example.json), never
  // hardcoded, so the same app binary works across environments.
  static const _clientId = String.fromEnvironment('b2c.clientId');
  static const _authority = String.fromEnvironment('b2c.authority');
  static const _redirectUri = String.fromEnvironment('b2c.redirectUri');
  static const _rawScopes = String.fromEnvironment('b2c.scopes');
  static const _tokenExchangeUrl = String.fromEnvironment('tokenExchange.url');
  static const _userProfileUrl = String.fromEnvironment('userProfile.url');

  static List<String> get _scopes =>
      _rawScopes.split(RegExp(r'[,\s]+')).where((s) => s.isNotEmpty).toList();

  static bool get _isConfigured =>
      _clientId.isNotEmpty &&
      _authority.isNotEmpty &&
      _redirectUri.isNotEmpty &&
      _scopes.isNotEmpty &&
      _tokenExchangeUrl.isNotEmpty &&
      _userProfileUrl.isNotEmpty;

  PublicClientApplication? _pca;
  String _status = _isConfigured
      ? 'Ready to authenticate'
      : 'Add the B2C values via --dart-define-from-file=env/env.json, then rebuild';
  bool _busy = false;
  bool _b2cComplete = false;
  bool _exchangeComplete = false;
  GuestProfile? _profile;
  String? _b2cToken;
  String? _elevateToken;
  final List<String> _logs = [];
  final ScrollController _logScrollController = ScrollController();

  bool get _authenticated => _b2cComplete;

  @override
  void dispose() {
    _logScrollController.dispose();
    super.dispose();
  }

  void _log(String message) {
    final now = DateTime.now();
    final timestamp =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    _logs.add('[$timestamp] $message');
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_logScrollController.hasClients) return;
      _logScrollController.jumpTo(
        _logScrollController.position.maxScrollExtent,
      );
    });
  }

  // Decodes JWT claims locally for diagnostics only; never treat this as verified identity.
  String _decodedTokenClaims(String token) {
    final parts = token.split('.');
    if (parts.length < 2) return 'Token claims unavailable';
    try {
      var payload = parts[1];
      payload += '=' * ((4 - payload.length % 4) % 4);
      final claims =
          jsonDecode(utf8.decode(base64Url.decode(payload)))
              as Map<String, dynamic>;
      return 'Token claims (decoded locally; not signature-verified): '
          'iss=${claims['iss'] ?? 'missing'}, aud=${claims['aud'] ?? 'missing'}, '
          'sub=${claims['sub'] ?? 'missing'}, oid=${claims['oid'] ?? 'missing'}, '
          'email=${claims['email'] ?? claims['preferred_username'] ?? 'missing'}';
    } catch (_) {
      return 'Token claims unavailable';
    }
  }

  Future<PublicClientApplication> _getPca() async {
    return _pca ??= await PublicClientApplication.createPublicClientApplication(
      _clientId,
      authority: _authority,
      redirectUri: _redirectUri,
    );
  }

  Future<void> _signIn() async {
    if (!_isConfigured) {
      setState(() {
        _status =
            'Add the B2C values via --dart-define-from-file=env/env.json, then rebuild';
        _log(_status);
      });
      return;
    }
    setState(() {
      _busy = true;
      _status = 'Signing in with Microsoft Entra B2C';
      _log(_status);
    });
    try {
      final pca = await _getPca();
      final token = await pca.acquireToken(_scopes);
      setState(() {
        _b2cToken = token;
        _b2cComplete = true;
        _status = 'Authenticated; B2C access token received';
        _log(_status);
        _log(_decodedTokenClaims(token));
      });
    } on MsalException catch (e) {
      setState(() {
        _status = 'MSAL error: ${e.errorMessage}';
        _log(_status);
      });
    } catch (e) {
      setState(() {
        _status = 'Sign-in failed: $e';
        _log(_status);
      });
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _exchange() async {
    final b2cToken = _b2cToken;
    if (b2cToken == null) return;
    setState(() {
      _busy = true;
      _status = 'Exchanging the B2C token on the trusted backend';
      _log(_status);
    });
    try {
      final elevateToken = await _exchangeToken(b2cToken);
      setState(() {
        _elevateToken = elevateToken;
        _exchangeComplete = true;
        _status = 'Elevate token exchange complete';
        _log(_status);
      });
    } catch (e) {
      setState(() {
        _status = 'Token exchange failed: $e';
        _log(_status);
      });
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _loadProfile() async {
    final elevateToken = _elevateToken;
    if (elevateToken == null) return;
    setState(() {
      _busy = true;
      _status = 'Loading profile';
      _log(_status);
    });
    try {
      final profile = await _fetchGuestProfile(elevateToken);
      setState(() {
        _profile = profile;
        _status = 'Profile loaded';
        _log(
          '$_status: ${profile.firstName} ${profile.lastName} <${profile.email}>',
        );
      });
    } catch (e) {
      setState(() {
        _status = 'Profile load failed: $e';
        _log(_status);
      });
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _signOut() async {
    setState(() => _busy = true);
    try {
      await _pca?.logout();
    } catch (_) {
      // Ignore logout errors; local state is cleared regardless.
    }
    setState(() {
      _pca = null;
      _b2cToken = null;
      _elevateToken = null;
      _profile = null;
      _b2cComplete = false;
      _exchangeComplete = false;
      _busy = false;
      _status = 'Signed out';
      _log(_status);
    });
  }

  Future<String> _exchangeToken(String b2cToken) async {
    final response = await http.post(
      Uri.parse(_tokenExchangeUrl),
      headers: const {'Content-Type': 'application/json'},
      body: jsonEncode({'b2CToken': b2cToken}),
    );
    final body = response.body.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(response.body) as Map<String, dynamic>;
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final detail = body['detail'] as String?;
      throw Exception(
        detail?.isNotEmpty == true
            ? detail
            : 'Token exchange returned HTTP ${response.statusCode}',
      );
    }
    final accessToken = body['accessToken'] as String?;
    if (accessToken == null || accessToken.isEmpty) {
      throw Exception('Exchange response is missing accessToken');
    }
    return accessToken;
  }

  Future<GuestProfile> _fetchGuestProfile(String elevateAccessToken) async {
    final response = await http.get(
      Uri.parse(_userProfileUrl),
      headers: {
        'Accept': 'application/json',
        'Authorization': 'Bearer $elevateAccessToken',
      },
    );
    final body = response.body.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(response.body) as Map<String, dynamic>;
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final detail = body['detail'] as String?;
      throw Exception(
        detail?.isNotEmpty == true
            ? detail
            : 'User profile returned HTTP ${response.statusCode}',
      );
    }
    return GuestProfile.fromJson(body);
  }

  @override
  Widget build(BuildContext context) {
    final profile = _profile;
    final profileStatus = profile != null
        ? 'Name: ${profile.firstName} ${profile.lastName}\nEmail: ${profile.email}\nGuest ID: ${profile.guestId}'
        : (_exchangeComplete
              ? 'Ready to load profile'
              : 'Locked until token exchange');

    return Scaffold(
      appBar: AppBar(title: const Text('B2C MSAL Demo')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'B2C to Elevate',
              style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(_status),
            const SizedBox(height: 16),
            _StepButton(
              number: '1',
              label: 'B2C via MSAL',
              status: _b2cComplete
                  ? 'Authenticated; B2C access token received'
                  : (_isConfigured
                        ? 'Ready'
                        : 'Missing environment configuration'),
              enabled: _isConfigured && !_busy,
              onPressed: _signIn,
            ),
            _StepButton(
              number: '2',
              label: 'Elevate token exchange',
              status: _exchangeComplete
                  ? 'Exchange complete'
                  : (_b2cComplete
                        ? 'Ready to exchange token'
                        : 'Locked until B2C authentication'),
              enabled: _b2cComplete && !_busy,
              onPressed: _exchange,
            ),
            _StepButton(
              number: '3',
              label: 'Current user profile',
              status: profileStatus,
              enabled: _exchangeComplete && !_busy,
              onPressed: _loadProfile,
            ),
            const SizedBox(height: 16),
            const Text(
              'Feedback log',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: _logs.isEmpty
                    ? const Text('No activity yet.')
                    : ListView.builder(
                        controller: _logScrollController,
                        itemCount: _logs.length,
                        itemBuilder: (context, index) => Text(
                          _logs[index],
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 12,
                          ),
                        ),
                      ),
              ),
            ),
            const SizedBox(height: 8),
            if (_authenticated)
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: _busy ? null : _signOut,
                  icon: const Icon(Icons.logout),
                  label: const Text('Sign out'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _StepButton extends StatelessWidget {
  const _StepButton({
    required this.number,
    required this.label,
    required this.status,
    required this.enabled,
    required this.onPressed,
  });

  final String number;
  final String label;
  final String status;
  final bool enabled;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        leading: CircleAvatar(child: Text(number)),
        title: Text(label),
        subtitle: Text(status),
        enabled: enabled,
        onTap: enabled ? onPressed : null,
      ),
    );
  }
}
