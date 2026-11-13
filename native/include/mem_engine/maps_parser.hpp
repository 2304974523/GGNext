#pragma once
#ifndef MEM_ENGINE_MAPS_PARSER_HPP
#define MEM_ENGINE_MAPS_PARSER_HPP

#include "types.hpp"
#include <string>
#include <vector>

namespace mem_engine {

struct VmaRegion {
    uintptr_t   startAddress = 0;
    uintptr_t   endAddress   = 0;
    size_t      length       = 0;
    uint64_t    fileOffset   = 0;
    uint64_t    inode        = 0;
    uint32_t    regionFlag   = RegionFlag::O;
    bool        readable     = false;
    bool        writable     = false;
    bool        executable   = false;
    bool        isPrivate    = false;
    bool        isShared     = false;
    std::string dev;
    std::string pathname;

    bool contains(uintptr_t addr) const noexcept {
        return addr >= startAddress && addr < endAddress;
    }

    bool isScanSafe(uint32_t mask) const noexcept {
        if (!readable || length == 0) return false;
        if (regionFlag == RegionFlag::B) return false;
        return (mask == 0) || ((regionFlag & mask) != 0);
    }
};

struct SmapsDetail {
    size_t rssKB          = 0;
    size_t pssKB          = 0;
    size_t sharedDirtyKB  = 0;
    size_t privateDirtyKB = 0;
    size_t anonymousKB    = 0;
    size_t swapKB         = 0;
    size_t kernelPageKB   = 4;
};

class MapsParser {
public:
    // Parse /proc/[pid]/maps and classify every VMA into Jh, Ch, Ca, Cd, Cb, A, S, As, Xa, Xs, V, Ps, B, O
    static std::vector<VmaRegion> parseProcessMaps(int pid, uint32_t filterMask = 0, bool skipUnreadable = true);

    // Parse a raw maps buffer (used for both /proc/[pid]/maps and deterministic unit testing)
    static std::vector<VmaRegion> parseMapsContent(const std::string& content, uint32_t filterMask = 0, bool skipUnreadable = true);

    // Classify a single VMA entry given its attributes and the preceding VMA's pathname
    static uint32_t classifyVma(const VmaRegion& vma, const std::string& prevFileBackedPath);

    // On-demand /proc/[pid]/smaps query for a specific virtual address to disambiguate residency & anon/PSS
    static bool querySmapsForAddress(int pid, uintptr_t targetStart, SmapsDetail& outDetail);
};

} // namespace mem_engine

#endif // MEM_ENGINE_MAPS_PARSER_HPP
