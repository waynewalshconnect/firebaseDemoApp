// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_msal_app/main.dart';

void main() {
  testWidgets('B2C login entry renders', (WidgetTester tester) async {
    await tester.pumpWidget(const B2CMsalApp());

    expect(find.text('B2C to Elevate'), findsOneWidget);
    expect(find.text('Sign in with MSAL'), findsOneWidget);
  });
}
