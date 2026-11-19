#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

char nibbleToHex(uint8_t v) {
    return (v < 10) ? static_cast<char>('0' + v) : static_cast<char>('A' + (v - 10));
}

int hexToNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string bytesToHex(const uint8_t* data, size_t len) {
    std::string out;
    out.resize(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out[i * 2]     = nibbleToHex((data[i] >> 4) & 0x0F);
        out[i * 2 + 1] = nibbleToHex(data[i] & 0x0F);
    }
    return out;
}

std::vector<uint8_t> hexToBytes(const std::string& hex) {
    std::vector<uint8_t> out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        int hi = hexToNibble(hex[i]);
        int lo = hexToNibble(hex[i + 1]);
        if (hi < 0 || lo < 0) break;
        out.push_back(static_cast<uint8_t>((hi << 4) | lo));
    }
    return out;
}

std::string formatPerms(const mem_engine::VmaRegion& r) {
    std::string p = "----";
    if (r.readable)   p[0] = 'r';
    if (r.writable)   p[1] = 'w';
    if (r.executable) p[2] = 'x';
    p[3] = r.isShared ? 's' : 'p';
    return p;
}

} // namespace

int main(int argc, char** argv) {
    std::string spillDir = "/data/local/tmp";
    size_t threads = 4;
    if (argc >= 2 && argv[1] && argv[1][0] != '\0') {
        spillDir = argv[1];
    }
    if (argc >= 3 && argv[2]) {
        int t = std::atoi(argv[2]);
        if (t >= 1 && t <= 16) threads = static_cast<size_t>(t);
    }

    mem_engine::ProcIO io;
    mem_engine::SearchEngine scanner(io, threads, spillDir);
    mem_engine::ValueLocker locker(io, 10);
    mem_engine::CancellationToken cancelToken;
    std::vector<mem_engine::VmaRegion> cachedAllRegions;
    mem_engine::VmaIndex cachedVmaIndex;
    std::mutex progressMutex;

    auto makeProgressReporter = [&]() -> mem_engine::ProgressCallback {
        auto lastReport = std::make_shared<std::chrono::steady_clock::time_point>(
            std::chrono::steady_clock::now() - std::chrono::milliseconds(100)
        );
        return [&, lastReport](size_t done, size_t total) {
            auto now = std::chrono::steady_clock::now();
            std::lock_guard<std::mutex> lk(progressMutex);
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - *lastReport).count();
            if (elapsedMs >= 45 || done >= total) {
                *lastReport = now;
                std::printf("PROGRESS %zu %zu %zu\n", done, total, scanner.totalMatchedCount());
                std::fflush(stdout);
            }
        };
    };

    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (line.empty()) continue;

        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;

        if (cmd == "PING") {
            std::printf("OK PONG\n");
            std::fflush(stdout);
        } else if (cmd == "EXIT" || cmd == "QUIT") {
            locker.unlockAll();
            std::printf("OK BYE\n");
            std::fflush(stdout);
            break;
        } else if (cmd == "ATTACH") {
            int pid = 0;
            iss >> pid;
            locker.unlockAll();
            auto rc = io.attach(pid);
            if (rc == mem_engine::EngineErrc::OK) {
                scanner.refreshRegions();
                cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(pid, 0, false);
                cachedVmaIndex.rebuild(cachedAllRegions);
                std::printf("OK 0 %zu\n", cachedAllRegions.size());
            } else {
                cachedAllRegions.clear();
                cachedVmaIndex.rebuild(cachedAllRegions);
                std::printf("ERR %d\n", static_cast<int>(rc));
            }
            std::fflush(stdout);
        } else if (cmd == "SET_MASK") {
            uint32_t mask = 0;
            iss >> mask;
            scanner.setRegionMask(mask);
            if (io.pid() > 0) {
                scanner.refreshRegions();
            }
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "REGIONS") {
            int pid = io.pid();
            if (pid > 0) {
                cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(pid, 0, false);
                cachedVmaIndex.rebuild(cachedAllRegions);
            }
            std::printf("OK %zu\n", cachedAllRegions.size());
            for (const auto& r : cachedAllRegions) {
                std::string perms = formatPerms(r);
                const char* code = mem_engine::regionFlagToTag(r.regionFlag);
                std::string path = r.pathname.empty() ? "-" : r.pathname;
                std::printf(
                    "%llx %llx %s %s %s\n",
                    static_cast<unsigned long long>(r.startAddress),
                    static_cast<unsigned long long>(r.endAddress),
                    perms.c_str(),
                    code,
                    path.c_str()
                );
            }
            std::fflush(stdout);
        } else if (cmd == "READ") {
            std::string addrHex;
            size_t size = 0;
            iss >> addrHex >> size;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            if (size == 0 || size > 16 * 1024 * 1024) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            std::vector<uint8_t> buf(size, 0);
            size_t bytesRead = 0;
            io.readMemory(addr, buf.data(), size, &bytesRead);
            std::string hex = bytesToHex(buf.data(), size);
            std::printf("OK %s\n", hex.c_str());
            std::fflush(stdout);
        } else if (cmd == "BATCH_READ") {
            size_t count = 0;
            iss >> count;
            if (count == 0 || count > 8192) {
                std::printf("OK 0\n");
                std::fflush(stdout);
                continue;
            }
            std::vector<uintptr_t> addrs(count, 0);
            std::vector<size_t> sizes(count, 4);
            for (size_t i = 0; i < count; ++i) {
                std::string token;
                if (!(iss >> token)) {
                    count = i;
                    break;
                }
                size_t colonPos = token.find(':');
                if (colonPos != std::string::npos) {
                    addrs[i] = static_cast<uintptr_t>(std::strtoull(token.substr(0, colonPos).c_str(), nullptr, 16));
                    size_t sz = static_cast<size_t>(std::strtoull(token.substr(colonPos + 1).c_str(), nullptr, 10));
                    sizes[i] = (sz >= 1 && sz <= 8) ? sz : 4;
                } else {
                    addrs[i] = static_cast<uintptr_t>(std::strtoull(token.c_str(), nullptr, 16));
                    sizes[i] = 4;
                }
            }
            std::vector<uint8_t> slab(count * 8, 0);
            std::vector<mem_engine::BatchReadRange> ranges(count);
            for (size_t i = 0; i < count; ++i) {
                ranges[i].remoteAddr = addrs[i];
                ranges[i].length     = sizes[i];
                ranges[i].localBuf   = slab.data() + (i * 8);
                ranges[i].success    = false;
            }
            io.readBatchScattered(ranges);
            std::printf("OK %zu\n", count);
            for (size_t i = 0; i < count; ++i) {
                int64_t rawVal = 0;
                if (ranges[i].success) {
                    std::memcpy(&rawVal, slab.data() + (i * 8), sizes[i]);
                } else {
                    size_t bytesRead = 0;
                    if (io.readMemory(addrs[i], slab.data() + (i * 8), sizes[i], &bytesRead) == mem_engine::EngineErrc::OK && bytesRead > 0) {
                        ranges[i].success = true;
                        std::memcpy(&rawVal, slab.data() + (i * 8), sizes[i]);
                    }
                }
                std::printf(
                    "%llx %d %lld\n",
                    static_cast<unsigned long long>(addrs[i]),
                    ranges[i].success ? 1 : 0,
                    static_cast<long long>(rawVal)
                );
            }
            std::fflush(stdout);
        } else if (cmd == "WRITE") {
            std::string addrHex, hexBytes;
            iss >> addrHex >> hexBytes;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            if (bytes.empty()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            auto rc = io.writeMemory(addr, bytes.data(), bytes.size());
            if (rc == mem_engine::EngineErrc::OK) {
                std::printf("OK 1\n");
            } else {
                std::printf("ERR %d\n", static_cast<int>(rc));
            }
            std::fflush(stdout);
        } else if (cmd == "COPY") {
            std::string fromHex, toHex;
            size_t length = 0;
            iss >> fromHex >> toHex >> length;
            uintptr_t fromAddr = static_cast<uintptr_t>(std::strtoull(fromHex.c_str(), nullptr, 16));
            uintptr_t toAddr = static_cast<uintptr_t>(std::strtoull(toHex.c_str(), nullptr, 16));
            const size_t total = std::min<size_t>(length, 16 * 1024 * 1024);
            const size_t chunk = 64 * 1024;
            std::vector<uint8_t> buf(chunk);
            size_t copied = 0;
            while (copied < total) {
                size_t step = std::min(chunk, total - copied);
                size_t bytesRead = 0;
                auto rrc = io.readMemory(fromAddr + copied, buf.data(), step, &bytesRead);
                if (rrc != mem_engine::EngineErrc::OK || bytesRead == 0) break;
                auto wrc = io.writeMemory(toAddr + copied, buf.data(), bytesRead);
                if (wrc != mem_engine::EngineErrc::OK) break;
                copied += bytesRead;
            }
            std::printf("OK %zu\n", copied);
            std::fflush(stdout);
        } else if (cmd == "DUMP") {
            std::string startHex, endHex, outPath;
            iss >> startHex >> endHex;
            std::getline(iss >> std::ws, outPath);
            uintptr_t startAddr = static_cast<uintptr_t>(std::strtoull(startHex.c_str(), nullptr, 16));
            uintptr_t endAddr = static_cast<uintptr_t>(std::strtoull(endHex.c_str(), nullptr, 16));
            if (endAddr <= startAddr || outPath.empty()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            std::ofstream ofs(outPath, std::ios::out | std::ios::binary | std::ios::trunc);
            if (!ofs.is_open()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            const size_t total = static_cast<size_t>(std::min<uint64_t>(endAddr - startAddr, 64ULL * 1024ULL * 1024ULL));
            const size_t chunk = 64 * 1024;
            std::vector<char> buf(chunk, 0);
            size_t written = 0;
            while (written < total) {
                size_t step = std::min(chunk, total - written);
                size_t bytesRead = 0;
                std::memset(buf.data(), 0, step);
                io.readMemory(startAddr + written, buf.data(), step, &bytesRead);
                ofs.write(buf.data(), static_cast<std::streamsize>(step));
                written += step;
            }
            ofs.close();
            std::printf("OK %zu\n", written);
            std::fflush(stdout);
        } else if (cmd == "LOCK") {
            std::string addrHex, hexBytes;
            iss >> addrHex >> hexBytes;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            if (!bytes.empty()) {
                locker.lockAddress(addr, bytes.data(), bytes.size());
            }
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_CONFIG") {
            std::string addrHex, hexBytes;
            int dt = 2, freezeMode = 0;
            int64_t minVal = 0, maxVal = 0;
            iss >> addrHex >> hexBytes >> dt >> freezeMode >> minVal >> maxVal;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            if (!bytes.empty()) {
                locker.lockAddressConfigured(
                    addr,
                    bytes.data(),
                    bytes.size(),
                    static_cast<mem_engine::DataType>(dt),
                    static_cast<mem_engine::FreezeMode>(freezeMode),
                    minVal,
                    maxVal
                );
            }
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_PAUSE") {
            int paused = 0;
            iss >> paused;
            locker.setPaused(paused != 0);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_INTERVAL") {
            uint32_t ms = 10;
            iss >> ms;
            locker.setIntervalMs(ms);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "UNLOCK") {
            std::string addrHex;
            iss >> addrHex;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            locker.unlockAddress(addr);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "UNLOCK_ALL") {
            locker.unlockAll();
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "CLEAR_SESSION") {
            scanner.clearSession();
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "REMOVE_CANDIDATES") {
            size_t count = 0;
            iss >> count;
            if (count > 0 && count <= 100000) {
                std::vector<uint64_t> addrs;
                addrs.reserve(count);
                for (size_t i = 0; i < count; ++i) {
                    std::string hex;
                    if (!(iss >> hex)) break;
                    addrs.push_back(std::strtoull(hex.c_str(), nullptr, 16));
                }
                scanner.removeCandidates(addrs.data(), addrs.size());
            }
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_EXACT") {
            int dt = 0, aligned = 1;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps >> aligned;
            cancelToken.reset();
            scanner.searchExact(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, aligned != 0, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REFINE_EXACT") {
            int dt = 0;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps;
            cancelToken.reset();
            scanner.refineExact(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_RANGE") {
            int dt = 0, inclusive = 1, aligned = 1;
            int64_t iMin = 0, iMax = 0;
            double fMin = 0.0, fMax = 0.0;
            iss >> dt >> iMin >> iMax >> fMin >> fMax >> inclusive >> aligned;
            cancelToken.reset();
            scanner.searchRange(static_cast<mem_engine::DataType>(dt), iMin, iMax, fMin, fMax, inclusive != 0, aligned != 0, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REFINE_RANGE") {
            int dt = 0, inclusive = 1;
            int64_t iMin = 0, iMax = 0;
            double fMin = 0.0, fMax = 0.0;
            iss >> dt >> iMin >> iMax >> fMin >> fMax >> inclusive;
            cancelToken.reset();
            scanner.refineRange(static_cast<mem_engine::DataType>(dt), iMin, iMax, fMin, fMax, inclusive != 0, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_POINTER") {
            int dt = 0, aligned = 1;
            std::string targetHex;
            uint64_t maxOffset = 0;
            uint32_t protMask = mem_engine::PointerProtMask::ALL;
            iss >> dt >> targetHex >> maxOffset >> protMask >> aligned;
            uintptr_t targetAddr = static_cast<uintptr_t>(std::strtoull(targetHex.c_str(), nullptr, 16));
            cancelToken.reset();
            scanner.searchPointers(
                static_cast<mem_engine::DataType>(dt),
                targetAddr,
                maxOffset,
                protMask,
                aligned != 0,
                &cancelToken,
                makeProgressReporter()
            );
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REFINE_POINTER") {
            int dt = 0;
            std::string targetHex;
            uint64_t maxOffset = 0;
            uint32_t protMask = mem_engine::PointerProtMask::ALL;
            iss >> dt >> targetHex >> maxOffset >> protMask;
            uintptr_t targetAddr = static_cast<uintptr_t>(std::strtoull(targetHex.c_str(), nullptr, 16));
            cancelToken.reset();
            scanner.refinePointers(
                static_cast<mem_engine::DataType>(dt),
                targetAddr,
                maxOffset,
                protMask,
                &cancelToken,
                makeProgressReporter()
            );
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_GROUP" || cmd == "REFINE_GROUP") {
            int windowSize = 512, ordered = 0, aligned = 1, count = 0;
            iss >> windowSize >> ordered >> aligned >> count;
            mem_engine::GroupSearchQuery query;
            query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
            query.ordered = (ordered != 0);
            query.aligned = (aligned != 0);
            for (int i = 0; i < count; ++i) {
                int dt = 0, op = 0;
                int64_t iv = 0, iMax = 0;
                double fv = 0.0, fMax = 0.0;
                iss >> dt >> iv >> fv >> iMax >> fMax >> op;
                mem_engine::GroupFieldItem f;
                f.type = static_cast<mem_engine::DataType>(dt);
                f.iVal = iv;
                f.fVal = fv;
                f.iMax = iMax;
                f.fMax = fMax;
                f.op = static_cast<uint8_t>(op);
                f.epsilon = 1e-4;
                f.origIndex = static_cast<size_t>(i);
                query.fields.push_back(f);
            }
            cancelToken.reset();
            if (cmd == "REFINE_GROUP") {
                scanner.refineGroup(query, &cancelToken, makeProgressReporter());
            } else {
                scanner.searchGroup(query, &cancelToken, makeProgressReporter());
            }
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_PATTERN") {
            std::string patHex, maskStr;
            iss >> patHex >> maskStr;
            auto pat = hexToBytes(patHex);
            std::vector<uint8_t> maskBytes(pat.size(), 0xFF);
            for (size_t i = 0; i < std::min(pat.size(), maskStr.size()); ++i) {
                maskBytes[i] = (maskStr[i] == '?' || maskStr[i] == '.') ? 0x00 : 0xFF;
            }
            cancelToken.reset();
            scanner.searchBytePattern(pat, maskBytes, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_ENCODED") {
            int dt = 0, encMode = 0, aligned = 1;
            int64_t logicalInt = 0;
            double logicalFloat = 0.0;
            uint64_t keyOrMask = 0;
            iss >> dt >> encMode >> logicalInt >> logicalFloat >> keyOrMask >> aligned;
            cancelToken.reset();
            scanner.searchEncoded(
                static_cast<mem_engine::DataType>(dt),
                static_cast<mem_engine::EncodedMode>(encMode),
                logicalInt,
                logicalFloat,
                keyOrMask,
                aligned != 0,
                &cancelToken,
                makeProgressReporter()
            );
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REFINE_ENCODED") {
            int dt = 0, encMode = 0;
            int64_t logicalInt = 0;
            double logicalFloat = 0.0;
            uint64_t keyOrMask = 0;
            iss >> dt >> encMode >> logicalInt >> logicalFloat >> keyOrMask;
            cancelToken.reset();
            scanner.refineEncoded(
                static_cast<mem_engine::DataType>(dt),
                static_cast<mem_engine::EncodedMode>(encMode),
                logicalInt,
                logicalFloat,
                keyOrMask,
                &cancelToken,
                makeProgressReporter()
            );
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "FUZZY_INIT") {
            int dt = 0;
            iss >> dt;
            cancelToken.reset();
            scanner.startUnknownSnapshot(static_cast<mem_engine::DataType>(dt), &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "FUZZY_REFINE") {
            int deltaOp = 0;
            int64_t iDelta = 0;
            double fDelta = 0.0, eps = 1e-4;
            iss >> deltaOp >> iDelta >> fDelta >> eps;
            cancelToken.reset();
            scanner.refineFuzzy(static_cast<mem_engine::SearchOp>(deltaOp), iDelta, fDelta, eps, &cancelToken, makeProgressReporter());
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "GET_CANDIDATES") {
            size_t offset = 0, limit = 1000;
            iss >> offset >> limit;
            std::vector<mem_engine::CandidateEntry> slice;
            scanner.fetchCandidates(offset, limit, slice);
            std::printf("OK %zu\n", slice.size());
            for (const auto& c : slice) {
                uint64_t meta = static_cast<uint64_t>(c.type) |
                                (static_cast<uint64_t>(c.regionTag) << 8) |
                                (static_cast<uint64_t>(c.flags) << 16);
                std::printf(
                    "%llx %lld %llu\n",
                    static_cast<unsigned long long>(c.address),
                    static_cast<long long>(static_cast<int64_t>(c.rawValue)),
                    static_cast<unsigned long long>(meta)
                );
            }
            std::fflush(stdout);
        } else {
            std::printf("ERR UNKNOWN_CMD\n");
            std::fflush(stdout);
        }
    }
    return 0;
}
