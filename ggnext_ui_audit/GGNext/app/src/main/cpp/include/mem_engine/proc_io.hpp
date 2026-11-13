#pragma once
#ifndef MEM_ENGINE_PROC_IO_HPP
#define MEM_ENGINE_PROC_IO_HPP

#include "types.hpp"
#include <sys/uio.h>
#include <vector>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <unordered_map>
#include <atomic>

namespace mem_engine {

struct BatchReadRange {
    uintptr_t remoteAddr = 0;
    size_t    length     = 0;
    uint8_t*  localBuf   = nullptr;
    bool      success    = false;
};

class ProcIO {
public:
    ProcIO();
    explicit ProcIO(int pid);
    ~ProcIO();

    ProcIO(const ProcIO&) = delete;
    ProcIO& operator=(const ProcIO&) = delete;
    ProcIO(ProcIO&& other) noexcept;
    ProcIO& operator=(ProcIO&& other) noexcept;

    // Attach to target process (compliant with standard Linux/Android DAC & ptrace_may_access)
    EngineErrc attach(int pid);
    void detach() noexcept;

    int pid() const noexcept { return m_pid; }
    size_t pageSize() const noexcept { return m_pageSize; }
    size_t iovMax() const noexcept { return m_iovMax; }

    // Standard single-range read/write: process_vm_readv/writev -> /proc/[pid]/mem pread64/pwrite64 fallback
    EngineErrc readMemory(uintptr_t addr, void* buf, size_t size, size_t* outBytesRead = nullptr) const noexcept;
    EngineErrc writeMemory(uintptr_t addr, const void* buf, size_t size) const noexcept;

    // Fault-tolerant chunk read: if a chunk spans one or more PROT_NONE guard pages (EFAULT),
    // automatically isolates unmapped pages at m_pageSize granularity, zero-filling unmapped pages
    // and filling outValidPageMask (1 byte per page: 1 = readable, 0 = guard/EFAULT) in O(pages/IOV_MAX) syscalls!
    EngineErrc readChunkFaultTolerant(
        uintptr_t startAddr,
        uint8_t* buf,
        size_t size,
        std::vector<uint8_t>& outValidPageMask,
        size_t* outReadableBytes = nullptr
    ) const;

    // Page-batched iovec read for iterative refinement across scattered candidate addresses
    EngineErrc readBatchScattered(std::vector<BatchReadRange>& ranges) const noexcept;

    // Typed helpers
    template <typename T>
    EngineErrc readValue(uintptr_t addr, T& outVal) const noexcept {
        return readMemory(addr, &outVal, sizeof(T));
    }

    template <typename T>
    EngineErrc writeValue(uintptr_t addr, const T& val) const noexcept {
        return writeMemory(addr, &val, sizeof(T));
    }

    // String & Byte[] helpers
    EngineErrc readStringUTF8(uintptr_t addr, std::string& outStr, size_t maxLen = 256) const;
    EngineErrc writeStringUTF8(uintptr_t addr, const std::string& str, bool includeNull = true) const;
    EngineErrc readStringUTF16(uintptr_t addr, std::u16string& outStr, size_t maxChars = 256) const;
    EngineErrc writeStringUTF16(uintptr_t addr, const std::u16string& str, bool includeNull = true) const;

    static size_t querySystemPageSize() noexcept;
    static size_t querySystemIovMax() noexcept;

private:
    int    m_pid       = -1;
    int    m_memFd     = -1;
    size_t m_pageSize  = 4096;
    size_t m_iovMax    = 1024;
    mutable std::atomic<bool> m_pvmSupported{true};

    static EngineErrc mapErrnoToErrc(int err) noexcept;
};

// ============================================================================
// High-Precision Value Locker (for Automated QA Boundary Condition Testing)
// ============================================================================
class ValueLocker {
public:
    explicit ValueLocker(ProcIO& io, uint32_t intervalMs = 5);
    ~ValueLocker();

    ValueLocker(const ValueLocker&) = delete;
    ValueLocker& operator=(const ValueLocker&) = delete;

    void lockAddress(uintptr_t addr, const void* data, size_t size);
    template <typename T>
    void lockValue(uintptr_t addr, const T& val) {
        lockAddress(addr, &val, sizeof(T));
    }
    void unlockAddress(uintptr_t addr);
    void unlockAll();
    void setIntervalMs(uint32_t intervalMs);
    size_t lockedCount() const;

private:
    struct LockedItem {
        uintptr_t addr;
        std::vector<uint8_t> bytes;
    };

    void workerLoop();

    ProcIO& m_io;
    mutable std::mutex m_mutex;
    std::condition_variable m_cv;
    std::unordered_map<uintptr_t, LockedItem> m_items;
    uint32_t m_intervalMs = 5;
    bool m_running = true;
    std::thread m_worker;
};

} // namespace mem_engine

#endif // MEM_ENGINE_PROC_IO_HPP
