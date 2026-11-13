export interface AuditFinding {
  id: string;
  project: 'MemorySearch.zip' | 'hmem.zip';
  subsystem: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM';
  fileLocation: string;
  defectTitle: string;
  rootCause: string;
  impact: string;
  ourResolution: string;
}

export const STATIC_AUDIT_FINDINGS: AuditFinding[] = [
  {
    id: 'MS-01',
    project: 'MemorySearch.zip',
    subsystem: 'Cross-Process I/O & Guard Pages',
    severity: 'CRITICAL',
    fileLocation: 'Search.hpp:645, 732 & FuzzySearch.hpp:145, 463',
    defectTitle: 'Hardcoded 4KB Page Step & EFAULT Syscall Storm on 32MB Chunks',
    rootCause:
      'Reads 32MB chunks via ProcIO::pvmRead; when a single PROT_NONE guard page exists inside the 32MB range, process_vm_readv returns a partial read or -1 (EFAULT). pvmRead returns false (discarding valid partial bytes) and increments cur += 4096.',
    impact:
      'Triggers up to 8,192 redundant failing 32MB syscalls (~268 GB of wasted virtual I/O) per guard page and breaks on Android 15+ 16KB page-size kernels.',
    ourResolution:
      'ProcIO::readChunkFaultTolerant queries sysconf(_SC_PAGESIZE) dynamically and falls back to per-page iovec scatter-gather batches (up to IOV_MAX=1024 pages/syscall) when a chunk contains a guard page, isolating unmapped pages in O(pages/IOV_MAX) syscalls with zero lost valid pages.'
  },
  {
    id: 'MS-02',
    project: 'MemorySearch.zip',
    subsystem: 'Memory Management & Concurrency',
    severity: 'CRITICAL',
    fileLocation: 'MemMmap.cpp:49, 81–102',
    defectTitle: 'Invalid mmap() on /proc/[pid]/mem & Unsynchronized m_lastHit Data Race',
    rootCause:
      'MemMmap::mapOneRegion attempts mmap(nullptr, len, PROT_READ, MAP_SHARED, fd, start) on /proc/[pid]/mem (which Linux proc_reg_mmap rejects with ENODEV/EINVAL), silently falling back to malloc + pread64 one-time stale copies. Furthermore, MemMmap::read updates mutable MappedRegion* m_lastHit without a mutex or atomic across worker threads.',
    impact:
      'Undefined Behavior (data race on m_lastHit across 4 threads), huge Heap RSS duplication, and stale snapshot reads during multi-pass refinement.',
    ourResolution:
      'Eliminated fake /proc/[pid]/mem mmap; implemented MmapCandidateStore and MmapRegionSnapshotStore backed by anonymous O_TMPFILE / unlinked disk files in /tmp with MAP_SHARED, capping scanner Heap RSS delta below 5 MB even on 3GB full-memory snapshots.'
  },
  {
    id: 'MS-03',
    project: 'MemorySearch.zip',
    subsystem: 'Search Algorithms & Concurrency',
    severity: 'HIGH',
    fileLocation: 'FastSearch.hpp:1–290 & FuzzySearch.cpp:130–245',
    defectTitle: 'Zero SIMD Vectorization, Chunk Boundary Misses, and 1-Syscall-Per-Hit Refinement',
    rootCause:
      'Uses scalar Boyer-Moore-Horspool / memcmp (no ARM NEON), performs bitwise memcmp for floats (no epsilon tolerance), splits chunks without boundary overlap, runs FuzzySearch single-threaded, and issues 1 pread64/pvmRead syscall per candidate in refineIndividual.',
    impact:
      'Misses unaligned values straddling chunk boundaries, misses slightly imprecise IEEE-754 floats, and suffers massive kernel context-switch overhead when refining >10,000 candidates.',
    ourResolution:
      'Implemented dual-path SimdKernel (ARM64 NEON vld1q_u8 + vreinterpretq + vmaxvq_u8/u32 & x86_64 auto-vectorized loops), configurable float/double epsilon tolerance, overlap-padded 4MB chunks (overlap = maxPatternSpan - 1), and page-coalesced iovec refinement.'
  },
  {
    id: 'MS-04',
    project: 'MemorySearch.zip',
    subsystem: 'VMA Classification & Process Filtering',
    severity: 'HIGH',
    fileLocation: 'ProcMap.hpp:85–175 & Process.cpp:15–90',
    defectTitle: 'Incomplete VMA Classifier & Missing Android Multi-User UID / Zygote Filtering',
    rootCause:
      'ProcMap::classify only marks unnamed mappings as RANGE_ANONYMOUS (misclassifying any [anon:foo] as RANGE_OTHER), lacks Ps (PPSSPP) and modern Android Scudo/GWP-ASan/ART region tags, and Process.cpp only reads /proc/[pid]/cmdline with DT_DIR checks.',
    impact:
      'Misses anonymous [anon:*] allocations, fails on filesystems returning DT_UNKNOWN in readdir(), and pollutes process lists with native root daemons.',
    ourResolution:
      'Implemented 14-tag deterministic VMA state machine (Jh, Ch, Ca, Cd, Cb, A, S, As, Xa, Xs, V, Ps, B, O) with on-demand smaps residency parsing and Android multi-user UID (uid % 100000 in 10000..19999 / 90000..99999) + Zygote parent verification.'
  },
  {
    id: 'HM-01',
    project: 'hmem.zip',
    subsystem: 'Cross-Process I/O & Compliance',
    severity: 'CRITICAL',
    fileLocation: 'kpm.rs:1–180 & v2p.rs:1–95',
    defectTitle: 'Hard Dependency on Non-Standard Kernel Rootkit Module (SYS_GETRESUID Hijack)',
    rootCause:
      'All memory reads/writes and page-table walks invoke syscall(SYS_GETRESUID, HMKPM_MAGIC, cmd, ptr) targeting a custom out-of-tree kernel module. Contains zero standard process_vm_readv or /proc/[pid]/mem implementation.',
    impact:
      'Completely non-functional on standard Android/Linux kernels and violates security compliance boundaries.',
    ourResolution:
      'Built strictly on standard Linux zero-copy process_vm_readv / process_vm_writev with seamless fallback to /proc/[pid]/mem pread64 / pwrite64 and explicit EPERM handling.'
  },
  {
    id: 'HM-02',
    project: 'hmem.zip',
    subsystem: 'Search Algorithms & Memory Safety',
    severity: 'CRITICAL',
    fileLocation: 'scanner.rs:286–298, 435, 1140–1310, 2096',
    defectTitle: 'Unbounded In-Memory Vec<CompactMatch> OOM, Coarse VMA Threading & Naive Group Anchor',
    rootCause:
      '1) Stores all Unknown scan matches in a heap Vec<CompactMatch> (16B/item -> 12GB RAM on 3GB int32 scan). 2) Partitions threads by VMA count rather than chunking inside large VMAs. 3) Casts unaligned uint8_t* directly to typed pointers in NEON loops. 4) scan_group is single-threaded and always anchors on target_items[0].',
    impact:
      'Immediate OOM kill on 3GB Unknown scans; severe thread starvation on large heap VMAs; 10x–45x slowdown when Group Search starts with a low-entropy field (e.g. byte:0).',
    ourResolution:
      'Disk-backed MmapRegionSnapshotStore & MmapCandidateStore (<5MB Heap RSS on 3GB snapshot); 4MB intra-VMA chunk work-stealing; strict-aliasing-safe vld1q_u8 loads; and multi-threaded Anchor-First Selectivity Scheduling for Ordered & Unordered Group Search.'
  }
];
