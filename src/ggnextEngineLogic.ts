export enum GGValueType {
  BYTE = 1,
  WORD = 2,
  DWORD = 4,
  XOR = 8,
  FLOAT = 16,
  QWORD = 32,
  DOUBLE = 64,
  AUTO = 127,
}

export enum GGSign {
  EQUAL = 0x04000000,
  NOT_EQUAL = 0x08000000,
  GREATER_EQ = 0x10000000,
  LESS_EQ = 0x20000000,
}

export interface GGParsedExpression {
  kind: 'SINGLE' | 'RANGE' | 'GROUP' | 'TEXT';
  numbers: bigint[];
  to: bigint[];
  types: GGValueType[];
  baseType: GGValueType;
  sign: GGSign;
  ordered: boolean;
  area: number;
  notRange: boolean;
  xorKey: bigint;
  textBytes: number[];
  textUtf16: boolean;
}

export interface GGMemoryCell {
  address: number;
  value: bigint;
  previousValue: bigint;
  flags: GGValueType;
  region: string;
  freeze: boolean;
  freezeType: number; // 0=normal, 1=inc, 2=dec, 3=range
  note: string;
  checked: boolean;
  pointer: number; // 0=none, 1=WX, 2=X, 4=R, 8=RO, 16=W
}

export interface AuditBugItem {
  id: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM';
  category: 'COMPILE_MISMATCH' | 'ENGINE_CORE' | 'MATH_LOGIC' | 'CONCURRENCY_IO' | 'UI_INTERACTION';
  file: string;
  lines: string;
  title: string;
  symptom: string;
  rootCause: string;
  beforeCode: string;
  afterCode: string;
}

const TYPE_SUFFIX_MAP: Record<string, GGValueType> = {
  B: GGValueType.BYTE,
  W: GGValueType.WORD,
  D: GGValueType.DWORD,
  X: GGValueType.XOR,
  F: GGValueType.FLOAT,
  Q: GGValueType.QWORD,
  E: GGValueType.DOUBLE,
  A: GGValueType.AUTO,
};

export function floatToRawBits(f: number): bigint {
  const buf = new ArrayBuffer(4);
  const view = new DataView(buf);
  view.setFloat32(0, f, true);
  return BigInt(view.getUint32(0, true));
}

export function rawBitsToFloat(bits: bigint): number {
  const buf = new ArrayBuffer(4);
  const view = new DataView(buf);
  view.setUint32(0, Number(bits & 0xffffffffn), true);
  return view.getFloat32(0, true);
}

export function doubleToRawBits(d: number): bigint {
  const buf = new ArrayBuffer(8);
  const view = new DataView(buf);
  view.setFloat64(0, d, true);
  return view.getBigUint64(0, true);
}

export function rawBitsToDouble(bits: bigint): number {
  const buf = new ArrayBuffer(8);
  const view = new DataView(buf);
  view.setBigUint64(0, bits, true);
  return view.getFloat64(0, true);
}

export function parseSingleNumber(token: string, defaultType: GGValueType): { bits: bigint; type: GGValueType } {
  let s = token.trim().replace(/_/g, '').replace(/,/g, '');
  if (!s) throw new Error('空数值');

  let hex = false;
  if (s.startsWith('0x') || s.startsWith('0X')) {
    hex = true;
    s = s.slice(2);
  } else if (s.endsWith('h') || s.endsWith('H')) {
    hex = true;
    s = s.slice(0, -1);
  }

  let resolvedType = defaultType;
  if (!hex && s.length > 1) {
    const lastChar = s[s.length - 1].toUpperCase();
    const prefix = s.slice(0, -1).trim();
    if (TYPE_SUFFIX_MAP[lastChar] !== undefined && /^[-+]?\d+(\.\d+)?$/.test(prefix)) {
      resolvedType = TYPE_SUFFIX_MAP[lastChar];
      s = prefix;
    } else if (/^[0-9a-fA-F]+$/.test(s) && /[a-fA-F]/.test(s)) {
      hex = true;
    }
  }

  if (resolvedType === GGValueType.FLOAT) {
    const fv = hex ? Number(BigInt('0x' + s)) : Number(s);
    if (Number.isNaN(fv)) throw new Error(`浮点数格式错误: ${token}`);
    return { bits: floatToRawBits(fv), type: resolvedType };
  }
  if (resolvedType === GGValueType.DOUBLE) {
    const dv = hex ? Number(BigInt('0x' + s)) : Number(s);
    if (Number.isNaN(dv)) throw new Error(`双精度格式错误: ${token}`);
    return { bits: doubleToRawBits(dv), type: resolvedType };
  }
  if (s.includes('.') && !hex) {
    const dv = Number(s);
    if (Number.isNaN(dv)) throw new Error(`小数格式错误: ${token}`);
    if (resolvedType === GGValueType.AUTO) {
      return { bits: floatToRawBits(dv), type: GGValueType.FLOAT };
    }
    return { bits: BigInt(Math.trunc(dv)), type: resolvedType };
  }

  const parsedBig = hex ? BigInt('0x' + s) : BigInt(s);
  return { bits: BigInt.asUintN(64, parsedBig), type: resolvedType };
}

export function parseGGExpression(
  raw: string,
  defaultType: GGValueType = GGValueType.DWORD,
  defaultSign: GGSign = GGSign.EQUAL
): GGParsedExpression {
  let s = raw.trim();
  if (!s) throw new Error('请输入搜索内容');

  // 1. Text search: 'utf8' or :utf8 or "utf16le" or ;utf16le
  if (s.startsWith("'") || s.startsWith('"') || s.startsWith(':') || s.startsWith(';')) {
    const utf16 = s.startsWith('"') || s.startsWith(';');
    const quoteChar = s[0];
    let body = s.slice(1);
    if ((quoteChar === "'" || quoteChar === '"') && body.endsWith(quoteChar) && body.length >= 1) {
      body = body.slice(0, -1);
    }
    if (!body) throw new Error('文本搜索内容不能为空');
    const bytes: number[] = [];
    if (utf16) {
      for (let i = 0; i < body.length; i++) {
        const code = body.charCodeAt(i);
        bytes.push(code & 0xff, (code >> 8) & 0xff);
      }
    } else {
      const encoded = new TextEncoder().encode(body);
      encoded.forEach((b) => bytes.push(b));
    }
    return {
      kind: 'TEXT',
      numbers: [BigInt(bytes.length)],
      to: [],
      types: [utf16 ? GGValueType.WORD : GGValueType.BYTE],
      baseType: utf16 ? GGValueType.WORD : GGValueType.BYTE,
      sign: defaultSign,
      ordered: true,
      area: bytes.length,
      notRange: false,
      xorKey: 0n,
      textBytes: bytes,
      textUtf16: utf16,
    };
  }

  // 2. Sign prefix override
  let sign = defaultSign;
  if (s.startsWith('!=')) {
    sign = GGSign.NOT_EQUAL;
    s = s.slice(2).trim();
  } else if (s.startsWith('>=')) {
    sign = GGSign.GREATER_EQ;
    s = s.slice(2).trim();
  } else if (s.startsWith('<=')) {
    sign = GGSign.LESS_EQ;
    s = s.slice(2).trim();
  } else if (s.startsWith('=')) {
    sign = GGSign.EQUAL;
    s = s.slice(1).trim();
  }

  // 3. Group search: 100;200;300:512 or 100;200;300::64
  if (s.includes(';')) {
    let ordered = false;
    let area = 512;
    let body = s;
    if (s.includes('::')) {
      ordered = true;
      const idx = s.lastIndexOf('::');
      area = Math.min(65536, Math.max(2, parseInt(s.slice(idx + 2), 10) || 512));
      body = s.slice(0, idx);
    } else if (s.includes(':')) {
      const idx = s.lastIndexOf(':');
      area = Math.min(65536, Math.max(2, parseInt(s.slice(idx + 1), 10) || 512));
      body = s.slice(0, idx);
    }
    const tokens = body
      .split(';')
      .map((t) => t.trim())
      .filter(Boolean);
    if (tokens.length < 2) throw new Error('联合搜索至少包含 2 个数值（以 ; 分隔）');
    const nums: bigint[] = [];
    const types: GGValueType[] = [];
    for (const tok of tokens) {
      const p = parseSingleNumber(tok, defaultType);
      nums.push(p.bits);
      types.push(p.type);
    }
    return {
      kind: 'GROUP',
      numbers: nums,
      to: [],
      types,
      baseType: defaultType,
      sign,
      ordered,
      area,
      notRange: false,
      xorKey: 0n,
      textBytes: [],
      textUtf16: false,
    };
  }

  // 4. Range search: A~B or A~~B
  if (s.includes('~')) {
    const notRange = s.includes('~~');
    const parts = s.split(notRange ? '~~' : '~');
    if (parts.length !== 2) throw new Error('范围格式应为 起始~结束 或 起始~~结束');
    const a = parseSingleNumber(parts[0], defaultType);
    const b = parseSingleNumber(parts[1], a.type);
    return {
      kind: 'RANGE',
      numbers: [a.bits],
      to: [b.bits],
      types: [a.type],
      baseType: a.type,
      sign,
      ordered: false,
      area: 0,
      notRange,
      xorKey: 0n,
      textBytes: [],
      textUtf16: false,
    };
  }

  // 5. XOR key syntax: 100X4 or 100x8
  const upper = s.toUpperCase();
  if (upper.includes('X') && !upper.startsWith('0X') && !upper.endsWith('X')) {
    const idx = upper.indexOf('X');
    const left = s.slice(0, idx);
    const right = s.slice(idx + 1);
    if (left && right) {
      const valParsed = parseSingleNumber(left, defaultType);
      const keyParsed = parseSingleNumber(right, GGValueType.DWORD);
      return {
        kind: 'SINGLE',
        numbers: [valParsed.bits],
        to: [],
        types: [valParsed.type],
        baseType: valParsed.type,
        sign,
        ordered: false,
        area: 0,
        notRange: false,
        xorKey: keyParsed.bits,
        textBytes: [],
        textUtf16: false,
      };
    }
  }

  // 6. Single value
  const single = parseSingleNumber(s, defaultType);
  return {
    kind: 'SINGLE',
    numbers: [single.bits],
    to: [],
    types: [single.type],
    baseType: single.type,
    sign,
    ordered: false,
    area: 0,
    notRange: false,
    xorKey: 0n,
    textBytes: [],
    textUtf16: false,
  };
}

/**
 * Signed & IEEE-754 aware comparison (fixes Bug #7 where Long.compareUnsigned broke negative ints & floats)
 */
export function compareTypedValues(rawA: bigint, rawB: bigint, type: GGValueType): number {
  switch (type) {
    case GGValueType.FLOAT: {
      const fa = rawBitsToFloat(rawA);
      const fb = rawBitsToFloat(rawB);
      if (Number.isNaN(fa) || Number.isNaN(fb)) return -1;
      if (Math.abs(fa - fb) <= 1e-4) return 0;
      return fa < fb ? -1 : 1;
    }
    case GGValueType.DOUBLE: {
      const da = rawBitsToDouble(rawA);
      const db = rawBitsToDouble(rawB);
      if (Number.isNaN(da) || Number.isNaN(db)) return -1;
      if (Math.abs(da - db) <= 1e-6) return 0;
      return da < db ? -1 : 1;
    }
    case GGValueType.BYTE: {
      const sa = BigInt.asIntN(8, rawA);
      const sb = BigInt.asIntN(8, rawB);
      return sa === sb ? 0 : sa < sb ? -1 : 1;
    }
    case GGValueType.WORD: {
      const sa = BigInt.asIntN(16, rawA);
      const sb = BigInt.asIntN(16, rawB);
      return sa === sb ? 0 : sa < sb ? -1 : 1;
    }
    case GGValueType.DWORD:
    case GGValueType.XOR: {
      const sa = BigInt.asIntN(32, rawA);
      const sb = BigInt.asIntN(32, rawB);
      return sa === sb ? 0 : sa < sb ? -1 : 1;
    }
    default: {
      const sa = BigInt.asIntN(64, rawA);
      const sb = BigInt.asIntN(64, rawB);
      return sa === sb ? 0 : sa < sb ? -1 : 1;
    }
  }
}

export function evaluateParsedMatch(rawVal: bigint, parsed: GGParsedExpression, cellType: GGValueType): boolean {
  const evalType = parsed.baseType === GGValueType.AUTO ? cellType : parsed.baseType;
  const logicalVal = parsed.xorKey !== 0n ? (rawVal ^ parsed.xorKey) : rawVal;

  if (parsed.kind === 'RANGE') {
    const cmp1 = compareTypedValues(logicalVal, parsed.numbers[0], evalType);
    const cmp2 = compareTypedValues(logicalVal, parsed.to[0], evalType);
    const inRange = (cmp1 >= 0 && cmp2 <= 0) || (cmp1 <= 0 && cmp2 >= 0);
    const baseMatch = parsed.notRange ? !inRange : inRange;
    return parsed.sign === GGSign.NOT_EQUAL ? !baseMatch : baseMatch;
  }

  const cmp = compareTypedValues(logicalVal, parsed.numbers[0], evalType);
  switch (parsed.sign) {
    case GGSign.EQUAL:
      return cmp === 0;
    case GGSign.NOT_EQUAL:
      return cmp !== 0;
    case GGSign.GREATER_EQ:
      return cmp >= 0;
    case GGSign.LESS_EQ:
      return cmp <= 0;
    default:
      return cmp === 0;
  }
}

export function formatCellValue(value: bigint, type: GGValueType): string {
  switch (type) {
    case GGValueType.FLOAT: {
      const f = rawBitsToFloat(value);
      return Number.isFinite(f) ? Number(f.toPrecision(6)).toString() : 'NaN';
    }
    case GGValueType.DOUBLE: {
      const d = rawBitsToDouble(value);
      return Number.isFinite(d) ? Number(d.toPrecision(10)).toString() : 'NaN';
    }
    case GGValueType.BYTE:
      return BigInt.asIntN(8, value).toString();
    case GGValueType.WORD:
      return BigInt.asIntN(16, value).toString();
    case GGValueType.DWORD:
    case GGValueType.XOR:
      return BigInt.asIntN(32, value).toString();
    default:
      return BigInt.asIntN(64, value).toString();
  }
}

export function formatHex(value: bigint, sizeBytes = 4): string {
  const mask = (1n << BigInt(sizeBytes * 8)) - 1n;
  return (value & mask).toString(16).toUpperCase().padStart(sizeBytes * 2, '0');
}

export function formatReverseHex(value: bigint, sizeBytes = 4): string {
  const bytes: string[] = [];
  for (let i = 0; i < sizeBytes; i++) {
    const b = Number((value >> BigInt(i * 8)) & 0xffn);
    bytes.push(b.toString(16).toUpperCase().padStart(2, '0'));
  }
  return bytes.join('');
}

export const GGNEXT_AUDIT_FINDINGS: AuditBugItem[] = [
  {
    id: 'BUG-01',
    severity: 'CRITICAL',
    category: 'COMPILE_MISMATCH',
    file: 'core/Session.kt & engine/MemoryEngine.kt',
    lines: 'Session.kt:316, 686-687, 801-820, 855, 904, 953, 1203, 1506, 1523',
    title: 'Session.kt 与 MemoryEngine.kt / Shell.kt / Models.kt 跨版本接口断裂导致无法编译',
    symptom: '工程直接编译报 18 处 Unresolved reference：Session.kt 调用了 engine.fuzzySearch、maskSearch、pointerSearch、setFreeze、tickFreeze、classifyPointers、x64，以及 OffsetSheet 访问了 private Session.engine。',
    rootCause: 'UI 状态层 Session.kt 演进到了新版签名，而底层 MemoryEngine.kt 仍停留在旧版签名，且 Session.engine 被声明为 private。',
    beforeCode: `private val engine = MemoryEngine() // CommonSheets.kt:835 无法访问 Session.engine
// MemoryEngine 缺少 fuzzySearch / maskSearch / pointerSearch / tickFreeze / setFreeze / classifyPointers`,
    afterCode: `internal val engine = MemoryEngine() // 对齐全部 15 个方法签名并桥接至 C++20 NativeMemBridge
fun fuzzySearch(...): SearchResult
fun maskSearch(...): SearchResult
fun pointerSearch(...): SearchResult`,
  },
  {
    id: 'BUG-02',
    severity: 'CRITICAL',
    category: 'COMPILE_MISMATCH',
    file: 'data/ProcessScanner.kt & model/Models.kt',
    lines: 'ProcessScanner.kt:38-45, Models.kt:49-60',
    title: 'ProcessScanner 构造 TargetProcess 时传入不存在的参数 icon 与 rssBytes',
    symptom: 'ProcessScanner.kt 编译失败：Cannot find a parameter with this name: icon / rssBytes。',
    rootCause: 'Models.kt 中 TargetProcess 定义为 (pid, packageName, label, x64, uid, sizeBytes, running, system, isSelf, hasIcon)，而 ProcessScanner 仍传入旧字段名。',
    beforeCode: `TargetProcess(
    pid = pid, packageName = pkg, label = label,
    icon = null, x64 = true, rssBytes = rss // 编译报错
)`,
    afterCode: `TargetProcess(
    pid = pid, packageName = pkg, label = label,
    x64 = is64Bit, uid = uid, sizeBytes = rssBytes,
    system = isSystemApp, isSelf = (pid == myPid), hasIcon = true
)`,
  },
  {
    id: 'BUG-03',
    severity: 'CRITICAL',
    category: 'COMPILE_MISMATCH',
    file: 'core/Session.kt & ui/sheet/SearchSheet.kt',
    lines: 'Session.kt:131-144, 852 & SearchSheet.kt:1175-1190',
    title: 'FuzzyState 缺失 onlyNth 字段导致模糊搜索“仅第 N 个”编译崩溃',
    symptom: 'Session.kt:852 (st.fuzzy.onlyNth) 与 SearchSheet.kt:1175 (fz.onlyNth) 均报 Unresolved reference: onlyNth。',
    rootCause: 'data class FuzzyState 声明中遗漏了 val onlyNth: String = "" 属性。',
    beforeCode: `data class FuzzyState(
    val extended: Boolean = false,
    val deltaText: String = "",
    val keyboardVisible: Boolean = true
)`,
    afterCode: `data class FuzzyState(
    val extended: Boolean = false,
    val deltaText: String = "",
    val onlyNth: String = "",
    val keyboardVisible: Boolean = true
)`,
  },
  {
    id: 'BUG-04',
    severity: 'CRITICAL',
    category: 'COMPILE_MISMATCH',
    file: 'ui/sheet/CommonSheets.kt & engine/Formatter.kt',
    lines: 'CommonSheets.kt:849, 851 & Formatter.kt:115-136',
    title: '偏移量计算器 (OffsetSheet) 调用了 Formatter 中不存在的 reverseHex 与 bytesToUtf16',
    symptom: 'CommonSheets.kt 第 849、851 行报 Unresolved reference: reverseHex / bytesToUtf16。',
    rootCause: 'Formatter.kt 仅实现了内部 rhex()，未暴露 публичный reverseHex(value, size) 与 bytesToUtf16(value, size)。',
    beforeCode: `val rhexStr = Formatter.reverseHex(qwordVal, 8) // Unresolved reference
val utf16Str = Formatter.bytesToUtf16(qwordVal, 8) // Unresolved reference`,
    afterCode: `fun reverseHex(value: Long, size: Int): String = rhex(value, size.coerceIn(1, 8))
fun bytesToUtf16(value: Long, size: Int = 8): String { /* UTF-16LE 解码 */ }`,
  },
  {
    id: 'BUG-05',
    severity: 'CRITICAL',
    category: 'ENGINE_CORE',
    file: 'engine/MemoryEngine.kt',
    lines: 'MemoryEngine.kt:76-125',
    title: '免 Root 模式 (WorkMode.NO_ROOT) 被硬编码强制降级为假随机数沙盒模拟',
    symptom: '在虚拟空间/双开沙箱或同 UID 自动化测试下选择“免 Root 模式”时，引擎直接进入随机数模拟，完全不读取真实进程内存。',
    rootCause: 'attach() 判断逻辑将 !isRootMode 直接等同于 simulated = true，忽略了同 UID 进程可通过 process_vm_readv 或 /proc/[pid]/mem 直接访问。',
    beforeCode: `if (!isRootMode) {
    simulated = true // 错误地将免 Root 全部打入假数据模拟
    return true
}`,
    afterCode: `// 优先通过 C++20 NativeMemBridge (process_vm_readv / pread64) 附加
val rc = NativeMemBridge.nativeAttach(handle, targetPid)
// 仅当 forceDemo = true 或所有跨进程通道均 EPERM 时才回退沙盒模拟`,
  },
  {
    id: 'BUG-06',
    severity: 'CRITICAL',
    category: 'ENGINE_CORE',
    file: 'core/Session.kt & engine/MemoryEngine.kt',
    lines: 'Session.kt:801-818 & MemoryEngine.kt:480-590',
    title: '文本搜索 (\'utf8\' / "utf16")、有序/无序联合搜索 (A;B:512) 与异或搜索 (AXK) 丢失参数',
    symptom: 'NumberParser 正确解析出了 textBytes、ordered、notRange，但 Session.runSearch() 构造 Criteria 时丢弃了这些字段，导致搜索结果全部错误。',
    rootCause: 'Session.runSearch() 未将 parsed.kind == TEXT、parsed.textBytes、parsed.ordered、parsed.notRange 传入 Criteria，且旧版引擎未对接 C++20 SIMD 模式/联合扫描。',
    beforeCode: `val criteria = MemoryEngine.Criteria(
    type = type, value = parsed.numbers.firstOrNull() ?: 0L,
    // 丢失了 isText, textBytes, textUtf16, ordered, notRange!
)`,
    afterCode: `val criteria = MemoryEngine.Criteria(
    ..., notRange = parsed.notRange, ordered = parsed.ordered,
    isText = (parsed.kind == NumberParser.Kind.TEXT),
    textBytes = parsed.textBytes, textUtf16 = parsed.textUtf16
) // 并桥接至 NativeMemBridge.nativeSearchPattern / nativeSearchGroup / nativeSearchEncoded`,
  },
  {
    id: 'BUG-07',
    severity: 'CRITICAL',
    category: 'MATH_LOGIC',
    file: 'engine/MemoryEngine.kt',
    lines: 'MemoryEngine.kt:642-700',
    title: '有符号负数与 IEEE-754 浮点数比较误用 Long.compareUnsigned',
    symptom: '搜索范围 -50~50、浮点数 >= 1.5F 或模糊搜索“增加了/减少了”时，负数 (-10 = 0xFFFFFFF6) 被当作无符号巨型正数 (4294967286 > 50)，结果完全反转。',
    rootCause: '旧版 matches() 对所有类型一律调用 Long.compareUnsigned(v, c.value)，未做符号位扩展与 Float.fromBits / Double.fromBits 解码。',
    beforeCode: `val cmp = java.lang.Long.compareUnsigned(v, c.value)
// -10 (0xFFFFFFF6) 被判定为大于 +50，且 Float 负数位模式方向相反！`,
    afterCode: `fun compareTypedValues(rawA: Long, rawB: Long, type: Int): Int = when (type and 0x7F) {
    ValueType.FLOAT -> Float.fromBits(rawA.toInt()).compareTo(Float.fromBits(rawB.toInt()))
    ValueType.DWORD -> rawA.toInt().toLong().compareTo(rawB.toInt().toLong())
    ...
}`,
  },
  {
    id: 'BUG-08',
    severity: 'HIGH',
    category: 'CONCURRENCY_IO',
    file: 'engine/MemoryEngine.kt',
    lines: 'MemoryEngine.kt:241-294, 370-408',
    title: '逐字节 RandomAccessFile 系统调用瓶颈与保护页中断整个 VMA 缺陷',
    symptom: '复制 16MB 内存触发 16,777,216 次 read/write 系统调用导致主界面 ANR 卡死；扫描遇到单个 ---p 保护页即中断整段内存区域。',
    rootCause: 'copyMemory 循环逐字节调用 read(addr, 1)/write(addr, 1)，且扫描循环在 read <= 0 时直接 break 而非按页跳过。',
    beforeCode: `for (i in 0 until bytesCount) {
    write(toAddress + i, 1, read(fromAddress + i, 1)) // 1600万次系统调用!
}`,
    afterCode: `// 优先调用 C++20 process_vm_readv/writev 零拷贝通道，JVM 回退采用 64KB 块缓冲
// 遇到不可读保护页时 offset += pageSize 单页跳过，绝不中断后续可读页`,
  },
  {
    id: 'BUG-09',
    severity: 'HIGH',
    category: 'CONCURRENCY_IO',
    file: 'engine/Formatter.kt',
    lines: 'Formatter.kt:27-31',
    title: 'Formatter.renderCache 使用非线程安全 LinkedHashMap(accessOrder=true) 引发并发崩溃',
    symptom: '后台 IO/Default 线程与 Compose UI 主线程同时格式化数值时抛出 ConcurrentModificationException 或链表死循环。',
    rootCause: 'LinkedHashMap 在 accessOrder = true 模式下每次 get() 都会修改内部双向链表结构，多线程并发读即构成数据竞争。',
    beforeCode: `private val renderCache = object : LinkedHashMap<Long, String>(4096, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 4096
}`,
    afterCode: `private val renderCache: MutableMap<Long, String> = Collections.synchronizedMap(
    object : LinkedHashMap<Long, String>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 4096
    }
)`,
  },
  {
    id: 'BUG-10',
    severity: 'HIGH',
    category: 'ENGINE_CORE',
    file: 'data/ProcessScanner.kt',
    lines: 'ProcessScanner.kt:20-85',
    title: 'ProcessScanner 仅枚举自身进程 + 硬编码假进程，无法发现真实用户/系统应用',
    symptom: '在真实设备上打开“选择进程”弹窗时，除了自身外全是硬编码假进程，无法看到正在运行的被测应用或多进程子实例 (:remote)。',
    rootCause: 'ProcessScanner.scan() 未实现动态遍历 /proc/[pid]/cmdline 与 /proc/[pid]/status UID 过滤。',
    beforeCode: `// 仅添加 Process.myPid() 后直接拼接 demoProcesses 硬编码列表`,
    afterCode: `// 集成 C++20 ProcessEnumerator.enumerateProcesses() + /proc 实时无缓存遍历
// 严格校验 AID_APP_START (10000..19999) 与多用户 userId*100000 规则，剔除内核线程`,
  },
  {
    id: 'BUG-11',
    severity: 'MEDIUM',
    category: 'UI_INTERACTION',
    file: 'ui/sheet/CommonSheets.kt',
    lines: 'CommonSheets.kt:435',
    title: '编辑弹窗 (EditSheet) 点击“恢复”按钮将 Float / Double 数值破坏为 IEEE-754 原始整数文本',
    symptom: '编辑 Float 类型条目 (例如 100.0F) 时点击“恢复”，输入框变成 "1120403456" (0x42C80000)，再次点击修改会写入错误巨型浮点数。',
    rootCause: '恢复按钮无视当前 selectedType，一律调用 Formatter.signed(firstItem.previousValue, size)。',
    beforeCode: `val origStr = Formatter.signed(firstItem.previousValue, ValueType.sizeOf(selectedType)).toString()`,
    afterCode: `val origStr = when (selectedType and 0x7F) {
    ValueType.FLOAT -> Formatter.floatText(firstItem.previousValue)
    ValueType.DOUBLE -> Formatter.doubleText(firstItem.previousValue)
    else -> Formatter.signed(firstItem.previousValue, ValueType.sizeOf(selectedType)).toString()
}`,
  },
  {
    id: 'BUG-12',
    severity: 'MEDIUM',
    category: 'UI_INTERACTION',
    file: 'ui/sheet/CommonSheets.kt',
    lines: 'CommonSheets.kt:714',
    title: '“更改地址/类型”弹窗 (AddressEditSheet) 点击确定完全忽略用户输入的新地址',
    symptom: '用户在“更改地址 / 类型”弹窗中输入新十六进制地址并点击“确定”后，条目地址毫无变化。',
    rootCause: '确定按钮回调仅写了 { Session.closeSheet() }，从未解析 addr.text 并更新条目。',
    beforeCode: `RoseButton("确定", Modifier.fillMaxWidth(), filled = true) { Session.closeSheet() }`,
    afterCode: `RoseButton("确定", Modifier.fillMaxWidth(), filled = true) {
    val parsedAddr = addr.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong()
    if (item != null && parsedAddr != null && parsedAddr != item.address) {
        Session.changeAddress(item, parsedAddr, item.flags)
    } else Session.closeSheet()
}`,
  },
  {
    id: 'BUG-13',
    severity: 'MEDIUM',
    category: 'UI_INTERACTION',
    file: 'core/Session.kt',
    lines: 'Session.kt:670-676, 1551',
    title: '内存页跳转硬编码显示 "Ca" 区域标签 & 进程暂停开关未发送 SIGSTOP/SIGCONT 信号',
    symptom: '无论跳转到 Java Heap (Jh) 还是栈 (S) 或代码段 (Xa)，内存浏览器右侧一律显示紫色的 "Ca"；点击暂停进程仅切换 UI 图标，目标进程仍在后台运行。',
    rootCause: 'goto() 构造 MemoryItem 时硬编码 region = "Ca"；toggleProcessPause() 仅翻转布尔值 processPaused。',
    beforeCode: `region = "Ca" // Session.kt:1551 硬编码
// toggleProcessPause() 未执行 kill -STOP / -CONT`,
    afterCode: `region = engine.regionCodeAt(rowAddr) // 动态查询 C++20 VMA 区间树
// toggleProcessPause() 异步发送 kill -STOP <pid> / kill -CONT <pid>`,
  },
  {
    id: 'BUG-14',
    severity: 'MEDIUM',
    category: 'UI_INTERACTION',
    file: 'ui/components/Keyboard.kt & ui/sheet/CommonSheets.kt',
    lines: 'Keyboard.kt:312 & CommonSheets.kt:2595, 2656',
    title: '内置键盘多字符键字号缩放运算符优先级错误 & 保存列表文件管理空壳',
    symptom: '调大或调小全局字体缩放时，内置键盘上的 "fx"、"123" 键字号固定不变；“保存/载入列表文件”点击确定仅弹 Toast 不读写文件；语言列表缺失繁體中文。',
    rootCause: 'if (label.length >= 2) 13.5f else 15.5f * fontScale 中乘法仅作用于 else 分支；FileSheet 未绑定文件持久化逻辑。',
    beforeCode: `fontSize = (if (label.length >= 2) 13.5f else 15.5f * LocalUiMetrics.current.fontScale).sp`,
    afterCode: `fontSize = ((if (label.length >= 2) 13.5f else 15.5f) * LocalUiMetrics.current.fontScale).sp
// FileSheet 对接 Session.saveOrLoadListFile(tf.text)，LanguageSheet 补齐繁體中文选项`,
  },
];
