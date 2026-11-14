#include "mem_engine/proc_io.hpp"
#include <fcntl.h>
#include <unistd.h>
#include <sys/uio.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <algorithm>
#include <chrono>

namespace mem_engine {

size_t ProcIO::querySystemPageSize() noexcept {
    long ps = ::sysconf(_SC_PAGESIZE);
    if (ps > 0 && (static_cast<size_t>(ps) & (static_cast<size_t>(ps) - 1)) == 0) {
        return static_cast<size_t>(ps);
    }
    return 4096;
}

size_t ProcIO::querySystemIovMax() noexcept {
    long im = ::sysconf(_SC_IOV_MAX);
    if (im > 0 && im <= 4096) {
        return static_cast<size_t>(im);
    }
    return 1024;
}

EngineErrc ProcIO::mapErrnoToErrc(int err) noexcept {
    switch (err) {
        case 0:      return EngineErrc::OK;
        case EPERM:
        case EACCES: return EngineErrc::ERR_PERM;
        case ESRCH:  return EngineErrc::ERR_SRCH;
        case EFAULT:
        case EIO:    return EngineErrc::ERR_FAULT;
        case ENOMEM: return EngineErrc::ERR_NOMEM;
        default:     return EngineErrc::ERR_INVAL;
    }
}

ProcIO::ProcIO()
    : m_pid(-1),
      m_memFd(-1),
      m_pageSize(querySystemPageSize()),
      m_iovMax(querySystemIovMax()) {}

ProcIO::ProcIO(int pid) : ProcIO() {
    attach(pid);
}

ProcIO::~ProcIO() {
    detach();
}

ProcIO::ProcIO(ProcIO&& other) noexcept
    : m_pid(other.m_pid),
      m_memFd(other.m_memFd),
      m_pageSize(other.m_pageSize),
      m_iovMax(other.m_iovMax),
      m_pvmSupported(other.m_pvmSupported.load(std::memory_order_relaxed)) {
    other.m_pid = -1;
    other.m_memFd = -1;
}

ProcIO& ProcIO::operator=(ProcIO&& other) noexcept {
    if (this != &other) {
        detach();
        m_pid = other.m_pid;
        m_memFd = other.m_memFd;
        m_pageSize = other.m_pageSize;
        m_iovMax = other.m_iovMax;
        m_pvmSupported.store(other.m_pvmSupported.load(std::memory_order_relaxed), std::memory_order_relaxed);
        other.m_pid = -1;
        other.m_memFd = -1;
    }
    return *this;
}

EngineErrc ProcIO::attach(int pid) {
    detach();
    if (pid <= 0) return EngineErrc::ERR_INVAL;

    // Verify process existence in /proc/[pid]
    char procDir[64];
    std::snprintf(procDir, sizeof(procDir), "/proc/%d", pid);
    if (::access(procDir, F_OK) != 0) {
        return mapErrnoToErrc(errno);
    }

    m_pid = pid;

    // Open /proc/[pid]/mem as fallback channel (RAII managed)
    char memPath[64];
    std::snprintf(memPath, sizeof(memPath), "/proc/%d/mem", pid);
    m_memFd = ::open(memPath, O_RDWR | O_CLOEXEC);
    if (m_memFd < 0) {
        m_memFd = ::open(memPath, O_RDONLY | O_CLOEXEC);
    }
    if (m_memFd < 0 && (errno == EPERM || errno == EACCES)) {
        // Check if process_vm_readv is also blocked by EPERM
        uint8_t dummy = 0;
        struct iovec l_iov{&dummy, 0};
        struct iovec r_iov{nullptr, 0};
        if (::process_vm_readv(m_pid, &l_iov, 1, &r_iov, 1, 0) < 0 && (errno == EPERM || errno == EACCES)) {
            m_pid = -1;
            return EngineErrc::ERR_PERM;
        }
    }

    return EngineErrc::OK;
}

void ProcIO::detach() noexcept {
    if (m_memFd >= 0) {
        ::close(m_memFd);
        m_memFd = -1;
    }
    m_pid = -1;
}

EngineErrc ProcIO::readMemory(uintptr_t addr, void* buf, size_t size, size_t* outBytesRead) const noexcept {
    if (outBytesRead) *outBytesRead = 0;
    if (m_pid <= 0 || !buf) return EngineErrc::ERR_INVAL;
    if (size == 0) return EngineErrc::OK;

    // Check for address + size overflow
    if (addr + size < addr) return EngineErrc::ERR_INVAL;

    if (m_pvmSupported.load(std::memory_order_relaxed)) {
        struct iovec localIov{buf, size};
        struct iovec remoteIov{reinterpret_cast<void*>(addr), size};
        ssize_t nread = ::process_vm_readv(m_pid, &localIov, 1, &remoteIov, 1, 0);
        if (nread == static_cast<ssize_t>(size)) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::OK;
        }
        if (nread > 0) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::ERR_FAULT; // Partial read stopped at unmapped/PROT_NONE page boundary
        }
        int err = errno;
        if (err == ENOSYS) {
            m_pvmSupported.store(false, std::memory_order_relaxed);
        } else if (err == EPERM || err == EACCES || err == ESRCH) {
            return mapErrnoToErrc(err);
        }
    }

    // Fallback to /proc/[pid]/mem pread64
    if (m_memFd >= 0) {
        ssize_t nread = ::pread64(m_memFd, buf, size, static_cast<off64_t>(addr));
        if (nread == static_cast<ssize_t>(size)) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::OK;
        }
        if (nread > 0) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::ERR_FAULT;
        }
        return mapErrnoToErrc(errno);
    }

    return EngineErrc::ERR_FAULT;
}

EngineErrc ProcIO::writeMemory(uintptr_t addr, const void* buf, size_t size) const noexcept {
    if (m_pid <= 0 || !buf) return EngineErrc::ERR_INVAL;
    if (size == 0) return EngineErrc::OK;
    if (addr + size < addr) return EngineErrc::ERR_INVAL;

    if (m_pvmSupported.load(std::memory_order_relaxed)) {
        struct iovec localIov{const_cast<void*>(buf), size};
        struct iovec remoteIov{reinterpret_cast<void*>(addr), size};
        ssize_t nwritten = ::process_vm_writev(m_pid, &localIov, 1, &remoteIov, 1, 0);
        if (nwritten == static_cast<ssize_t>(size)) {
            return EngineErrc::OK;
        }
        int err = errno;
        if (err == ENOSYS) {
            m_pvmSupported.store(false, std::memory_order_relaxed);
        } else if (err == EPERM || err == EACCES || err == ESRCH) {
            return mapErrnoToErrc(err);
        }
    }

    if (m_memFd >= 0) {
        ssize_t nwritten = ::pwrite64(m_memFd, buf, size, static_cast<off64_t>(addr));
        if (nwritten == static_cast<ssize_t>(size)) {
            return EngineErrc::OK;
        }
        return mapErrnoToErrc(errno);
    }

    return EngineErrc::ERR_FAULT;
}

EngineErrc ProcIO::readChunkFaultTolerant(
    uintptr_t startAddr,
    uint8_t* buf,
    size_t size,
    std::vector<uint8_t>& outValidPageMask,
    size_t* outReadableBytes
) const {
    if (outReadableBytes) *outReadableBytes = 0;
    if (m_pid <= 0 || !buf || size == 0) return EngineErrc::ERR_INVAL;
    if (startAddr + size < startAddr) return EngineErrc::ERR_INVAL;

    const size_t ps = m_pageSize;
    uintptr_t firstPage = (startAddr / ps) * ps;
    uintptr_t endAddr   = startAddr + size;
    uintptr_t lastPage  = ((endAddr + ps - 1) / ps) * ps;
    size_t numPages     = (lastPage - firstPage) / ps;

    outValidPageMask.assign(numPages, 1);

    // Fast path: attempt single contiguous read first
    size_t fastRead = 0;
    EngineErrc rc = readMemory(startAddr, buf, size, &fastRead);
    if (rc == EngineErrc::OK) {
        if (outReadableBytes) *outReadableBytes = size;
        return EngineErrc::OK;
    }
    if (rc == EngineErrc::ERR_PERM || rc == EngineErrc::ERR_SRCH) {
        outValidPageMask.assign(numPages, 0);
        return rc;
    }

    // A guard page (PROT_NONE / EFAULT) exists inside [startAddr, startAddr + size).
    // Note: process_vm_readv stops at the first faulting iovec/page and returns the number of
    // bytes transferred before the fault! We leverage this exact kernel behavior to skip only
    // the faulting page(s) in O(guard_pages) syscalls instead of O(total_pages)!
    size_t totalValidBytes = 0;
    size_t curOffset = 0;

    while (curOffset < size) {
        // If fastRead > 0 on the first iteration, those bytes are already valid in buf!
        if (fastRead > 0) {
            totalValidBytes += fastRead;
            curOffset += fastRead;
            fastRead = 0;
            if (curOffset >= size) break;
        }

        // Align the faulting address to the next page boundary and mark current page invalid
        uintptr_t faultAddr = startAddr + curOffset;
        size_t pageIdx = (faultAddr - firstPage) / ps;
        if (pageIdx < numPages) {
            outValidPageMask[pageIdx] = 0;
        }

        uintptr_t nextPageAddr = ((faultAddr / ps) + 1) * ps;
        size_t skipBytes = (nextPageAddr > faultAddr)
            ? std::min(static_cast<size_t>(nextPageAddr - faultAddr), size - curOffset)
            : std::min(ps, size - curOffset);

        std::memset(buf + curOffset, 0, skipBytes);
        curOffset += skipBytes;

        if (curOffset >= size) break;

        // Try reading the remainder of the chunk from nextPageAddr
        size_t remSize = size - curOffset;
        size_t subRead = 0;
        EngineErrc subRc = readMemory(startAddr + curOffset, buf + curOffset, remSize, &subRead);
        if (subRc == EngineErrc::OK) {
            totalValidBytes += remSize;
            break;
        } else if (subRc == EngineErrc::ERR_PERM || subRc == EngineErrc::ERR_SRCH) {
            return subRc;
        }
        fastRead = subRead;
    }

    if (outReadableBytes) *outReadableBytes = totalValidBytes;
    return totalValidBytes > 0 ? EngineErrc::OK : EngineErrc::ERR_FAULT;
}

EngineErrc ProcIO::readBatchScattered(std::vector<BatchReadRange>& ranges) const noexcept {
    if (m_pid <= 0) return EngineErrc::ERR_INVAL;
    if (ranges.empty()) return EngineErrc::OK;

    const size_t maxBatch = m_iovMax;
    std::vector<struct iovec> localIovs;
    std::vector<struct iovec> remoteIovs;
    localIovs.reserve(std::min(ranges.size(), maxBatch));
    remoteIovs.reserve(std::min(ranges.size(), maxBatch));

    size_t idx = 0;
    while (idx < ranges.size()) {
        localIovs.clear();
        remoteIovs.clear();
        size_t batchStart = idx;
        size_t expectedBytes = 0;

        while (idx < ranges.size() && localIovs.size() < maxBatch) {
            auto& r = ranges[idx];
            if (r.length > 0 && r.localBuf != nullptr) {
                localIovs.push_back({r.localBuf, r.length});
                remoteIovs.push_back({reinterpret_cast<void*>(r.remoteAddr), r.length});
                expectedBytes += r.length;
            }
            ++idx;
        }

        if (localIovs.empty()) continue;

        ssize_t nread = -1;
        if (m_pvmSupported.load(std::memory_order_relaxed)) {
            nread = ::process_vm_readv(
                m_pid,
                localIovs.data(),
                static_cast<unsigned long>(localIovs.size()),
                remoteIovs.data(),
                static_cast<unsigned long>(remoteIovs.size()),
                0
            );
        }

        if (nread == static_cast<ssize_t>(expectedBytes)) {
            for (size_t k = batchStart; k < idx; ++k) {
                ranges[k].success = true;
            }
        } else {
            // Partial batch read or fallback needed: resolve per-item in this batch
            for (size_t k = batchStart; k < idx; ++k) {
                auto& r = ranges[k];
                r.success = (readMemory(r.remoteAddr, r.localBuf, r.length) == EngineErrc::OK);
            }
        }
    }

    return EngineErrc::OK;
}

EngineErrc ProcIO::readStringUTF8(uintptr_t addr, std::string& outStr, size_t maxLen) const {
    outStr.clear();
    if (maxLen == 0) return EngineErrc::OK;
    std::vector<char> buf(maxLen, 0);
    size_t bytesRead = 0;
    EngineErrc rc = readMemory(addr, buf.data(), maxLen, &bytesRead);
    if (rc != EngineErrc::OK && bytesRead == 0) return rc;
    size_t actualLen = ::strnlen(buf.data(), bytesRead);
    outStr.assign(buf.data(), actualLen);
    return EngineErrc::OK;
}

EngineErrc ProcIO::writeStringUTF8(uintptr_t addr, const std::string& str, bool includeNull) const {
    size_t len = includeNull ? (str.size() + 1) : str.size();
    return writeMemory(addr, str.c_str(), len);
}

EngineErrc ProcIO::readStringUTF16(uintptr_t addr, std::u16string& outStr, size_t maxChars) const {
    outStr.clear();
    if (maxChars == 0) return EngineErrc::OK;
    std::vector<char16_t> buf(maxChars, 0);
    size_t bytesRead = 0;
    EngineErrc rc = readMemory(addr, buf.data(), maxChars * sizeof(char16_t), &bytesRead);
    if (rc != EngineErrc::OK && bytesRead < sizeof(char16_t)) return rc;
    size_t charsRead = bytesRead / sizeof(char16_t);
    size_t len = 0;
    while (len < charsRead && buf[len] != 0) ++len;
    outStr.assign(buf.data(), len);
    return EngineErrc::OK;
}

EngineErrc ProcIO::writeStringUTF16(uintptr_t addr, const std::u16string& str, bool includeNull) const {
    size_t chars = includeNull ? (str.size() + 1) : str.size();
    return writeMemory(addr, str.c_str(), chars * sizeof(char16_t));
}

// ============================================================================
// ValueLocker Implementation
// ============================================================================
ValueLocker::ValueLocker(ProcIO& io, uint32_t intervalMs)
    : m_io(io), m_intervalMs(intervalMs), m_running(true) {
    m_worker = std::thread(&ValueLocker::workerLoop, this);
}

ValueLocker::~ValueLocker() {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_running = false;
    }
    m_cv.notify_all();
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

void ValueLocker::lockAddress(uintptr_t addr, const void* data, size_t size) {
    if (!data || size == 0) return;
    m_io.writeMemory(addr, data, size);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        LockedItem item;
        item.addr = addr;
        item.bytes.assign(static_cast<const uint8_t*>(data), static_cast<const uint8_t*>(data) + size);
        m_items[addr] = std::move(item);
    }
    m_cv.notify_one();
}

void ValueLocker::unlockAddress(uintptr_t addr) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_items.erase(addr);
}

void ValueLocker::unlockAll() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_items.clear();
}

void ValueLocker::setIntervalMs(uint32_t intervalMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_intervalMs = std::max(1u, intervalMs);
    m_cv.notify_one();
}

size_t ValueLocker::lockedCount() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_items.size();
}

void ValueLocker::workerLoop() {
    std::unique_lock<std::mutex> lock(m_mutex);
    while (m_running) {
        if (m_items.empty()) {
            m_cv.wait(lock, [this]() { return !m_running || !m_items.empty(); });
            if (!m_running) break;
        }

        std::vector<LockedItem> snapshot;
        snapshot.reserve(m_items.size());
        for (const auto& kv : m_items) {
            snapshot.push_back(kv.second);
        }
        uint32_t sleepMs = m_intervalMs;

        lock.unlock();
        for (const auto& item : snapshot) {
            m_io.writeMemory(item.addr, item.bytes.data(), item.bytes.size());
        }
        lock.lock();

        if (!m_running) break;
        m_cv.wait_for(lock, std::chrono::milliseconds(sleepMs));
    }
}

} // namespace mem_engine
