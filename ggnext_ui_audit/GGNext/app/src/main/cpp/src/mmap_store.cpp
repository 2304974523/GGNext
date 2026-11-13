#include "mem_engine/mmap_store.hpp"
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>

namespace mem_engine {

namespace {

int createAnonymousTempFile(const std::string& dir) {
    int fd = -1;
#ifdef O_TMPFILE
    fd = ::open(dir.c_str(), O_RDWR | O_TMPFILE | O_EXCL | O_CLOEXEC, 0600);
    if (fd >= 0) return fd;
#endif
    std::string tmpl = dir + "/memengine_spill_XXXXXX";
    std::vector<char> buf(tmpl.begin(), tmpl.end());
    buf.push_back('\0');
    fd = ::mkostemp(buf.data(), O_CLOEXEC);
    if (fd >= 0) {
        ::unlink(buf.data()); // Unlink immediately so file is auto-reclaimed by kernel on close/exit
    }
    return fd;
}

} // anonymous namespace

// ============================================================================
// MmapCandidateStore Implementation
// ============================================================================
MmapCandidateStore::MmapCandidateStore(size_t inMemoryThreshold, const std::string& spillDir)
    : m_threshold(inMemoryThreshold), m_spillDir(spillDir) {
    m_ramVec.reserve(std::min<size_t>(m_threshold, 65536));
}

MmapCandidateStore::~MmapCandidateStore() {
    clear();
}

MmapCandidateStore::MmapCandidateStore(MmapCandidateStore&& other) noexcept
    : m_threshold(other.m_threshold),
      m_spillDir(std::move(other.m_spillDir)),
      m_ramVec(std::move(other.m_ramVec)),
      m_count(other.m_count),
      m_isMmap(other.m_isMmap),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_mmapCapacityBytes(other.m_mmapCapacityBytes) {
    other.m_count = 0;
    other.m_isMmap = false;
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_mmapCapacityBytes = 0;
}

MmapCandidateStore& MmapCandidateStore::operator=(MmapCandidateStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_threshold = other.m_threshold;
        m_spillDir = std::move(other.m_spillDir);
        m_ramVec = std::move(other.m_ramVec);
        m_count = other.m_count;
        m_isMmap = other.m_isMmap;
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_mmapCapacityBytes = other.m_mmapCapacityBytes;

        other.m_count = 0;
        other.m_isMmap = false;
        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_mmapCapacityBytes = 0;
    }
    return *this;
}

bool MmapCandidateStore::transitionToMmap(size_t requiredCount) {
    m_fd = createAnonymousTempFile(m_spillDir);
    if (m_fd < 0) return false;

    size_t capCount = std::max(requiredCount * 2, m_threshold * 2);
    size_t capBytes = capCount * sizeof(CandidateEntry);

    if (::ftruncate(m_fd, static_cast<off_t>(capBytes)) != 0) {
        ::close(m_fd);
        m_fd = -1;
        return false;
    }

    void* ptr = ::mmap(nullptr, capBytes, PROT_READ | PROT_WRITE, MAP_SHARED, m_fd, 0);
    if (ptr == MAP_FAILED) {
        ::close(m_fd);
        m_fd = -1;
        return false;
    }

    ::madvise(ptr, capBytes, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<CandidateEntry*>(ptr);
    m_mmapCapacityBytes = capBytes;
    m_isMmap = true;

    if (!m_ramVec.empty()) {
        std::memcpy(m_mmapPtr, m_ramVec.data(), m_ramVec.size() * sizeof(CandidateEntry));
        // Release heap vector memory completely
        std::vector<CandidateEntry>().swap(m_ramVec);
    }

    return true;
}

bool MmapCandidateStore::growMmap(size_t requiredCount) {
    size_t neededBytes = requiredCount * sizeof(CandidateEntry);
    if (neededBytes <= m_mmapCapacityBytes) return true;

    size_t newCapBytes = std::max(m_mmapCapacityBytes * 2, neededBytes + (16 * 1024 * 1024));
    if (::ftruncate(m_fd, static_cast<off_t>(newCapBytes)) != 0) {
        return false;
    }

#ifdef __linux__
    void* newPtr = ::mremap(m_mmapPtr, m_mmapCapacityBytes, newCapBytes, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    ::munmap(m_mmapPtr, m_mmapCapacityBytes);
    void* newPtr = ::mmap(nullptr, newCapBytes, PROT_READ | PROT_WRITE, MAP_SHARED, m_fd, 0);
    if (newPtr == MAP_FAILED) {
        m_mmapPtr = nullptr;
        return false;
    }
#endif

    m_mmapPtr = static_cast<CandidateEntry*>(newPtr);
    m_mmapCapacityBytes = newCapBytes;
    return true;
}

bool MmapCandidateStore::append(const CandidateEntry& entry) {
    return appendBatch(&entry, 1);
}

bool MmapCandidateStore::appendBatch(const CandidateEntry* entries, size_t count) {
    if (count == 0) return true;
    size_t newCount = m_count + count;

    if (!m_isMmap) {
        if (newCount <= m_threshold) {
            m_ramVec.insert(m_ramVec.end(), entries, entries + count);
            m_count = newCount;
            return true;
        }
        if (!transitionToMmap(newCount)) {
            return false;
        }
    }

    if (!growMmap(newCount)) {
        return false;
    }

    std::memcpy(m_mmapPtr + m_count, entries, count * sizeof(CandidateEntry));
    m_count = newCount;
    return true;
}

const CandidateEntry& MmapCandidateStore::operator[](size_t idx) const noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

CandidateEntry& MmapCandidateStore::operator[](size_t idx) noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

const CandidateEntry* MmapCandidateStore::data() const noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

CandidateEntry* MmapCandidateStore::data() noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

void MmapCandidateStore::sortByAddress() {
    if (m_count <= 1) return;
    CandidateEntry* base = data();
    std::sort(base, base + m_count, [](const CandidateEntry& a, const CandidateEntry& b) {
        if (a.address != b.address) return a.address < b.address;
        return a.type < b.type;
    });
}

void MmapCandidateStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_mmapCapacityBytes);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_mmapCapacityBytes = 0;
    m_isMmap = false;
    m_count = 0;
    std::vector<CandidateEntry>().swap(m_ramVec);
}

// ============================================================================
// MmapRegionSnapshotStore Implementation
// ============================================================================
MmapRegionSnapshotStore::MmapRegionSnapshotStore(const std::string& spillDir)
    : m_spillDir(spillDir) {}

MmapRegionSnapshotStore::~MmapRegionSnapshotStore() {
    clear();
}

MmapRegionSnapshotStore::MmapRegionSnapshotStore(MmapRegionSnapshotStore&& other) noexcept
    : m_spillDir(std::move(other.m_spillDir)),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_capacity(other.m_capacity),
      m_writeOffset(other.m_writeOffset),
      m_blocks(std::move(other.m_blocks)) {
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_capacity = 0;
    other.m_writeOffset = 0;
}

MmapRegionSnapshotStore& MmapRegionSnapshotStore::operator=(MmapRegionSnapshotStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_spillDir = std::move(other.m_spillDir);
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_capacity = other.m_capacity;
        m_writeOffset = other.m_writeOffset;
        m_blocks = std::move(other.m_blocks);

        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_capacity = 0;
        other.m_writeOffset = 0;
    }
    return *this;
}

bool MmapRegionSnapshotStore::openStore(size_t reserveBytes) {
    clear();
    m_fd = createAnonymousTempFile(m_spillDir);
    if (m_fd < 0) return false;

    size_t cap = std::max<size_t>(reserveBytes, 16 * 1024 * 1024);
    if (::ftruncate(m_fd, static_cast<off_t>(cap)) != 0) {
        ::close(m_fd);
        m_fd = -1;
        return false;
    }

    void* ptr = ::mmap(nullptr, cap, PROT_READ, MAP_SHARED, m_fd, 0);
    if (ptr == MAP_FAILED) {
        ::close(m_fd);
        m_fd = -1;
        return false;
    }

    ::madvise(ptr, cap, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<uint8_t*>(ptr);
    m_capacity = cap;
    m_writeOffset = 0;
    return true;
}

bool MmapRegionSnapshotStore::ensureCapacity(size_t totalBytesNeeded) {
    if (m_fd < 0) {
        return openStore(totalBytesNeeded);
    }
    if (totalBytesNeeded <= m_capacity) return true;

    size_t newCap = std::max(m_capacity * 2, totalBytesNeeded + (32 * 1024 * 1024));
    if (::ftruncate(m_fd, static_cast<off_t>(newCap)) != 0) return false;

#ifdef __linux__
    void* newPtr = ::mremap(m_mmapPtr, m_capacity, newCap, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    ::munmap(m_mmapPtr, m_capacity);
    void* newPtr = ::mmap(nullptr, newCap, PROT_READ, MAP_SHARED, m_fd, 0);
    if (newPtr == MAP_FAILED) {
        m_mmapPtr = nullptr;
        return false;
    }
#endif

    m_mmapPtr = static_cast<uint8_t*>(newPtr);
    m_capacity = newCap;
    return true;
}

bool MmapRegionSnapshotStore::appendRegionChunk(uintptr_t startAddr, const uint8_t* data, size_t len, uint32_t regionFlag) {
    if (len == 0 || !data) return true;
    if (!ensureCapacity(m_writeOffset + len)) return false;

    size_t written = 0;
    while (written < len) {
        ssize_t n = ::pwrite(m_fd, data + written, len - written, static_cast<off_t>(m_writeOffset + written));
        if (n <= 0) return false;
        written += static_cast<size_t>(n);
    }

    SnapshotBlock blk;
    blk.startAddress = startAddr;
    blk.length       = len;
    blk.fileOffset   = m_writeOffset;
    blk.regionFlag   = regionFlag;
    m_blocks.push_back(blk);

    m_writeOffset += len;
    return true;
}

const uint8_t* MmapRegionSnapshotStore::getBlockData(const SnapshotBlock& block) const noexcept {
    if (!m_mmapPtr || block.fileOffset + block.length > m_writeOffset) return nullptr;
    return m_mmapPtr + block.fileOffset;
}

void MmapRegionSnapshotStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_capacity);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_capacity = 0;
    m_writeOffset = 0;
    m_blocks.clear();
}

} // namespace mem_engine
