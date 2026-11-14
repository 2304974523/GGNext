import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  GGValueType,
  GGSign,
  parseGGExpression,
  compareTypedValues,
  evaluateParsedMatch,
  floatToRawBits,
  formatCellValue,
  formatReverseHex,
  classifyAndroidProcessCandidate,
  GGNEXT_AUDIT_FINDINGS,
} from './ggnextEngineLogic.ts';

describe('GGNext + C++20 MemEngine Integration Pure Logic Tests', () => {
  test('parses single integer, hex suffix h, and type suffixes (D/F/Q)', () => {
    const d = parseGGExpression('12345', GGValueType.DWORD);
    assert.equal(d.kind, 'SINGLE');
    assert.equal(d.numbers[0], 12345n);

    const hex = parseGGExpression('1A2Bh', GGValueType.DWORD);
    assert.equal(hex.numbers[0], 0x1a2bn);

    const flt = parseGGExpression('100.5F', GGValueType.DWORD);
    assert.equal(flt.baseType, GGValueType.FLOAT);
    assert.equal(formatCellValue(flt.numbers[0], GGValueType.FLOAT), '100.5');
  });

  test('parses Range (A~B) and Inverse Range (A~~B) including negative bounds', () => {
    const rng = parseGGExpression('-50~100', GGValueType.DWORD);
    assert.equal(rng.kind, 'RANGE');
    assert.equal(rng.notRange, false);

    // Verify Bug #7 fix: -10 (0xFFFFFFF6) must be inside [-50, 100]
    const negTenRaw = BigInt.asUintN(64, -10n);
    assert.equal(evaluateParsedMatch(negTenRaw, rng, GGValueType.DWORD), true);
    assert.equal(evaluateParsedMatch(BigInt.asUintN(64, -80n), rng, GGValueType.DWORD), false);

    const inv = parseGGExpression('10~~20', GGValueType.DWORD);
    assert.equal(inv.notRange, true);
    assert.equal(evaluateParsedMatch(15n, inv, GGValueType.DWORD), false);
    assert.equal(evaluateParsedMatch(25n, inv, GGValueType.DWORD), true);
  });

  test('parses Unordered (;) and Ordered (::) Group Search with mixed types', () => {
    const unordered = parseGGExpression('100D;250D;1.5F:256', GGValueType.DWORD);
    assert.equal(unordered.kind, 'GROUP');
    assert.equal(unordered.ordered, false);
    assert.equal(unordered.area, 256);
    assert.deepEqual(unordered.types, [GGValueType.DWORD, GGValueType.DWORD, GGValueType.FLOAT]);

    const ordered = parseGGExpression('10;20;30::64', GGValueType.DWORD);
    assert.equal(ordered.kind, 'GROUP');
    assert.equal(ordered.ordered, true);
    assert.equal(ordered.area, 64);
  });

  test('parses XOR key syntax (100X444444) and evaluates XOR-encoded cells', () => {
    const xorExpr = parseGGExpression('1000X5A5A', GGValueType.DWORD);
    assert.equal(xorExpr.xorKey, 23130n);
    const encodedCell = 1000n ^ 23130n;
    assert.equal(evaluateParsedMatch(encodedCell, xorExpr, GGValueType.DWORD), true);
    assert.equal(evaluateParsedMatch(1000n, xorExpr, GGValueType.DWORD), false);
  });

  test('parses UTF-8 and UTF-16LE text search expressions', () => {
    const utf8 = parseGGExpression("'HP'", GGValueType.BYTE);
    assert.equal(utf8.kind, 'TEXT');
    assert.equal(utf8.textUtf16, false);
    assert.deepEqual(utf8.textBytes, [0x48, 0x50]);

    const utf16 = parseGGExpression('"HP"', GGValueType.WORD);
    assert.equal(utf16.kind, 'TEXT');
    assert.equal(utf16.textUtf16, true);
    assert.deepEqual(utf16.textBytes, [0x48, 0x00, 0x50, 0x00]);
  });

  test('compareTypedValues handles IEEE-754 floats and signed integers accurately', () => {
    const fNeg = floatToRawBits(-12.5);
    const fPos = floatToRawBits(3.25);
    assert.equal(compareTypedValues(fNeg, fPos, GGValueType.FLOAT), -1);
    assert.equal(compareTypedValues(fPos, floatToRawBits(3.25001), GGValueType.FLOAT), 0);

    // Reverse hex formatting (Bug #4 fix)
    assert.equal(formatReverseHex(0x11223344n, 4), '44332211');
  });

  test('classifyAndroidProcessCandidate strictly filters Linux/HAL daemons and separates USER vs SYSTEM apps (Bug #16 fix)', () => {
    // Rejects all Linux/HAL daemons seen in user screenshot
    assert.equal(classifyAndroidProcessCandidate('media.codec', 1046, false, false, false).accepted, false);
    assert.equal(classifyAndroidProcessCandidate('media.metrics', 1013, false, false, false).accepted, false);
    assert.equal(classifyAndroidProcessCandidate('android.hardware.sensors@2.0-service', 1000, false, false, false).accepted, false);
    assert.equal(classifyAndroidProcessCandidate('vendor.qti.hardware.display.composer-service', 1000, false, false, false).accepted, false);
    assert.equal(classifyAndroidProcessCandidate('vendor.qti.hardwa', 1000, false, false, false).accepted, false);
    assert.equal(classifyAndroidProcessCandidate('/system/bin/surfaceflinger', 1000, false, false, false).accepted, false);

    // Accepts real user app (e.g. me.hd.ggtutorial) as USER
    const userApp = classifyAndroidProcessCandidate('me.hd.ggtutorial', 10294, true, false, false);
    assert.equal(userApp.accepted, true);
    assert.equal(userApp.category, 'USER');

    // Accepts real system app (e.g. com.android.systemui) as SYSTEM
    const sysApp = classifyAndroidProcessCandidate('com.android.systemui', 10085, true, true, false);
    assert.equal(sysApp.accepted, true);
    assert.equal(sysApp.category, 'SYSTEM');
  });

  test('audit catalog contains all 18 documented bugs with non-empty diffs', () => {
    assert.equal(GGNEXT_AUDIT_FINDINGS.length, 18);
    for (const item of GGNEXT_AUDIT_FINDINGS) {
      assert.ok(item.beforeCode.length > 5);
      assert.ok(item.afterCode.length > 5);
    }
  });
});

