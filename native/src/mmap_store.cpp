#include "mem_engine/mmap_store.hpp"
#include <sys/mman.h>
#include <unistd.h>
#include <cstring>
#include <algorithm>
#include <utility>

namespace mem_engine {

MmapCandidateStore::MmapCandidateStore(size_t ramLimitEntries, std::string tempDir)
    : m_ramLimit(std::min(ramLimitEntries, MAX_STORED_CANDIDATES)),
      m_tempDir(std::move(tempDir)) {}

MmapCandidateStore::~MmapCandidateStore() {
    cleanupMmap();
}

MmapCandidateStore::MmapCandidateStore(MmapCandidateStore&& other) noexcept {
    swap(other);
}

MmapCandidateStore& MmapCandidateStore::operator=(MmapCandidateStore&& other) noexcept {
    if (this != &other) {
        clear();
        swap(other);
    }
    return *this;
}

void MmapCandidateStore::swap(MmapCandidateStore& other) noexcept {
    std::swap(m_ramLimit, other.m_ramLimit);
    std::swap(m_tempDir, other.m_tempDir);
    std::swap(m_ramBuffer, other.m_ramBuffer);
    std::swap(m_usingMmap, other.m_usingMmap);
    std::swap(m_mmapPtr, other.m_mmapPtr);
    std::swap(m_mmapCapacity, other.m_mmapCapacity);
    std::swap(m_size, other.m_size);
}

void MmapCandidateStore::cleanupMmap() noexcept {
    if (m_mmapPtr != nullptr && m_mmapPtr != MAP_FAILED) {
        ::munmap(m_mmapPtr, m_mmapCapacity * sizeof(CandidateEntry));
        m_mmapPtr = nullptr;
    }
    m_mmapCapacity = 0;
    m_usingMmap = false;
}

void MmapCandidateStore::clear() noexcept {
    cleanupMmap();
    m_ramBuffer.clear();
    m_ramBuffer.shrink_to_fit();
    m_size = 0;
}

void MmapCandidateStore::reserve(size_t expectedEntries) {
    const size_t capped = std::min(expectedEntries, MAX_STORED_CANDIDATES);
    if (!m_usingMmap && capped <= m_ramLimit) {
        m_ramBuffer.reserve(capped);
    } else if (!m_usingMmap && capped > m_ramLimit) {
        switchToAnonymousMmap(capped);
    } else if (m_usingMmap && capped > m_mmapCapacity) {
        growMmap(capped);
    }
}

bool MmapCandidateStore::switchToAnonymousMmap(size_t initialCapacity) {
    if (m_usingMmap) return true;

    size_t cap = std::min(MAX_STORED_CANDIDATES,
                          std::max({initialCapacity, m_ramLimit * 2, static_cast<size_t>(65536)}));
    size_t byteSize = cap * sizeof(CandidateEntry);

    void* ptr = ::mmap(nullptr, byteSize, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ptr == MAP_FAILED) {
        return false;
    }

    if (!m_ramBuffer.empty()) {
        const size_t toCopy = std::min(m_ramBuffer.size(), cap);
        std::memcpy(ptr, m_ramBuffer.data(), toCopy * sizeof(CandidateEntry));
        m_size = toCopy;
        m_ramBuffer.clear();
        m_ramBuffer.shrink_to_fit();
    }

    m_mmapPtr = ptr;
    m_mmapCapacity = cap;
    m_usingMmap = true;
    return true;
}

bool MmapCandidateStore::growMmap(size_t minCapacity) {
    if (!m_usingMmap) return false;
    if (minCapacity <= m_mmapCapacity) return true;
    if (m_mmapCapacity >= MAX_STORED_CANDIDATES) return false;

    size_t newCap = m_mmapCapacity;
    while (newCap < minCapacity && newCap < MAX_STORED_CANDIDATES) {
        newCap = std::min(MAX_STORED_CANDIDATES, newCap * 2);
    }
    size_t oldBytes = m_mmapCapacity * sizeof(CandidateEntry);
    size_t newBytes = newCap * sizeof(CandidateEntry);

#if defined(__linux__)
    void* newPtr = ::mremap(m_mmapPtr, oldBytes, newBytes, MREMAP_MAYMOVE);
    if (newPtr != MAP_FAILED) {
        m_mmapPtr = newPtr;
        m_mmapCapacity = newCap;
        return true;
    }
#endif

    void* fallbackPtr = ::mmap(nullptr, newBytes, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (fallbackPtr == MAP_FAILED) {
        return false;
    }
    if (m_size > 0) {
        std::memcpy(fallbackPtr, m_mmapPtr, m_size * sizeof(CandidateEntry));
    }
    ::munmap(m_mmapPtr, oldBytes);
    m_mmapPtr = fallbackPtr;
    m_mmapCapacity = newCap;
    return true;
}

void MmapCandidateStore::push_back(const CandidateEntry& entry) {
    appendBatch(&entry, 1);
}

void MmapCandidateStore::appendBatch(const CandidateEntry* entries, size_t count) {
    if (count == 0 || entries == nullptr) return;
    if (m_size >= MAX_STORED_CANDIDATES) return;

    const size_t allowed = std::min(count, MAX_STORED_CANDIDATES - m_size);
    if (allowed == 0) return;

    if (!m_usingMmap) {
        if (m_size + allowed <= m_ramLimit) {
            m_ramBuffer.insert(m_ramBuffer.end(), entries, entries + allowed);
            m_size += allowed;
            return;
        }
        if (!switchToAnonymousMmap(m_size + allowed)) {
            const size_t fallbackAllowed = (m_size < m_ramLimit) ? std::min(allowed, m_ramLimit - m_size) : 0;
            if (fallbackAllowed > 0) {
                m_ramBuffer.insert(m_ramBuffer.end(), entries, entries + fallbackAllowed);
                m_size += fallbackAllowed;
            }
            return;
        }
    }

    if (m_size + allowed > m_mmapCapacity) {
        if (!growMmap(m_size + allowed)) {
            return;
        }
    }

    auto* dst = static_cast<CandidateEntry*>(m_mmapPtr) + m_size;
    std::memcpy(dst, entries, allowed * sizeof(CandidateEntry));
    m_size += allowed;
}

const CandidateEntry* MmapCandidateStore::data() const noexcept {
    if (m_usingMmap) {
        return static_cast<const CandidateEntry*>(m_mmapPtr);
    }
    return m_ramBuffer.data();
}

CandidateEntry* MmapCandidateStore::data() noexcept {
    if (m_usingMmap) {
        return static_cast<CandidateEntry*>(m_mmapPtr);
    }
    return m_ramBuffer.data();
}

void MmapCandidateStore::sortByAddress() {
    if (m_size <= 1) return;
    CandidateEntry* ptr = data();
    // Fast check if already sorted (which is true when tasks are merged in chunk order)
    bool sorted = true;
    for (size_t i = 1; i < m_size; ++i) {
        if (ptr[i].address < ptr[i - 1].address) {
            sorted = false;
            break;
        }
    }
    if (!sorted) {
        std::sort(ptr, ptr + m_size, [](const CandidateEntry& a, const CandidateEntry& b) {
            return a.address < b.address;
        });
    }
}

// ============================================================================
// MmapRegionSnapshotStore (Anonymous Memory Snapshot)
// ============================================================================

MmapRegionSnapshotStore::MmapRegionSnapshotStore(std::string tempDir)
    : m_tempDir(std::move(tempDir)) {}

MmapRegionSnapshotStore::~MmapRegionSnapshotStore() {
    clear();
}

bool MmapRegionSnapshotStore::initialize() {
    clear();
    const size_t initCap = 16ULL * 1024ULL * 1024ULL; // 16 MB initial anonymous region
    void* ptr = ::mmap(nullptr, initCap, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ptr == MAP_FAILED) {
        m_mmapPtr = nullptr;
        return false;
    }
    m_mmapPtr = ptr;
    m_mmapCapacity = initCap;
    return true;
}

void MmapRegionSnapshotStore::clear() noexcept {
    if (m_mmapPtr != nullptr && m_mmapPtr != MAP_FAILED) {
        ::munmap(m_mmapPtr, m_mmapCapacity);
        m_mmapPtr = nullptr;
    }
    m_mmapCapacity = 0;
    m_totalBytes = 0;
    m_blocks.clear();
}

bool MmapRegionSnapshotStore::ensureCapacity(size_t requiredBytes) {
    if (requiredBytes <= m_mmapCapacity) return true;
    if (requiredBytes > MAX_SNAPSHOT_BYTES) return false;

    size_t newCap = std::max(m_mmapCapacity, static_cast<size_t>(16ULL * 1024ULL * 1024ULL));
    while (newCap < requiredBytes && newCap < MAX_SNAPSHOT_BYTES) {
        newCap = std::min(MAX_SNAPSHOT_BYTES, newCap * 2);
    }
    if (newCap < requiredBytes) return false;

#if defined(__linux__)
    if (m_mmapPtr != nullptr && m_mmapPtr != MAP_FAILED) {
        void* newPtr = ::mremap(m_mmapPtr, m_mmapCapacity, newCap, MREMAP_MAYMOVE);
        if (newPtr != MAP_FAILED) {
            m_mmapPtr = newPtr;
            m_mmapCapacity = newCap;
            return true;
        }
    }
#endif

    void* fallbackPtr = ::mmap(nullptr, newCap, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (fallbackPtr == MAP_FAILED) return false;
    if (m_mmapPtr != nullptr && m_mmapPtr != MAP_FAILED && m_totalBytes > 0) {
        std::memcpy(fallbackPtr, m_mmapPtr, m_totalBytes);
        ::munmap(m_mmapPtr, m_mmapCapacity);
    }
    m_mmapPtr = fallbackPtr;
    m_mmapCapacity = newCap;
    return true;
}

bool MmapRegionSnapshotStore::appendRegion(uint64_t startAddr, const uint8_t* data, size_t len, RegionFlag flag) {
    if (len == 0 || data == nullptr) return false;
    if (m_mmapPtr == nullptr && !initialize()) return false;

    const size_t newTotal = m_totalBytes + len;
    if (!ensureCapacity(newTotal)) {
        return false;
    }

    std::memcpy(static_cast<uint8_t*>(m_mmapPtr) + m_totalBytes, data, len);
    m_blocks.push_back(SnapshotRegionBlock{
        startAddr,
        len,
        m_totalBytes,
        flag
    });
    m_totalBytes = newTotal;
    return true;
}

const uint8_t* MmapRegionSnapshotStore::readBlockData(const SnapshotRegionBlock& block) const noexcept {
    if (m_mmapPtr == nullptr || m_mmapPtr == MAP_FAILED) return nullptr;
    if (block.fileOffset + block.byteLength > m_totalBytes) return nullptr;
    return static_cast<const uint8_t*>(m_mmapPtr) + block.fileOffset;
}

} // namespace mem_engine
