#include "mem_engine/maps_parser.hpp"
#include "mem_engine/memory_reader.hpp"
#include "mem_engine/process_scanner.hpp"
#include "mem_engine/search_engine.hpp"
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iomanip>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <mutex>

using namespace mem_engine;

namespace {

std::string toHex(const uint8_t* data, size_t len) {
    static const char* lut = "0123456789ABCDEF";
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out.push_back(lut[(data[i] >> 4) & 0xF]);
        out.push_back(lut[data[i] & 0xF]);
    }
    return out;
}

std::vector<uint8_t> fromHex(const std::string& hex) {
    std::vector<uint8_t> out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        std::string byteStr = hex.substr(i, 2);
        uint8_t b = static_cast<uint8_t>(std::strtoul(byteStr.c_str(), nullptr, 16));
        out.push_back(b);
    }
    return out;
}

DataType mapGgType(int ggFlags) {
    switch (ggFlags & 0x7F) {
        case 1:  return DataType::I8;
        case 2:  return DataType::I16;
        case 4:  return DataType::I32;
        case 8:  return DataType::XOR32;
        case 16: return DataType::F32;
        case 32: return DataType::I64;
        case 64: return DataType::F64;
        default: return DataType::I32;
    }
}

int unmapGgType(DataType dt) {
    switch (dt) {
        case DataType::I8:
        case DataType::U8:    return 1;
        case DataType::I16:
        case DataType::U16:   return 2;
        case DataType::I32:
        case DataType::U32:   return 4;
        case DataType::XOR32: return 8;
        case DataType::F32:   return 16;
        case DataType::I64:
        case DataType::U64:   return 32;
        case DataType::F64:   return 64;
    }
    return 4;
}

ScanValue makeScanValue(DataType dt, int64_t rawBits) {
    ScanValue sv{};
    if (dt == DataType::F32) {
        int32_t bits32 = static_cast<int32_t>(rawBits);
        float f = 0.0f;
        std::memcpy(&f, &bits32, sizeof(float));
        sv.f32 = f;
    } else if (dt == DataType::F64) {
        double d = 0.0;
        std::memcpy(&d, &rawBits, sizeof(double));
        sv.f64 = d;
    } else {
        sv.i64 = rawBits;
    }
    return sv;
}

int64_t extractRawBits(ScanValue sv, DataType dt) {
    if (dt == DataType::F32) {
        int32_t bits = 0;
        std::memcpy(&bits, &sv.f32, sizeof(int32_t));
        return static_cast<int64_t>(static_cast<uint32_t>(bits));
    } else if (dt == DataType::F64) {
        int64_t bits = 0;
        std::memcpy(&bits, &sv.f64, sizeof(int64_t));
        return bits;
    }
    return sv.i64;
}

/**
 * Throttled progress reporter that writes `PROGRESS <done> <total> <found>` lines
 * to stdout at most once every 50ms in a thread-safe manner.
 */
ProgressCallback makeProgressStream() {
    auto stateMutex = std::make_shared<std::mutex>();
    auto lastEmit = std::make_shared<std::chrono::steady_clock::time_point>(
        std::chrono::steady_clock::now() - std::chrono::milliseconds(100));

    return [stateMutex, lastEmit](size_t done, size_t total, size_t found) {
        auto now = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lk(*stateMutex);
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - *lastEmit).count();
        if (elapsedMs >= 50 || (total > 0 && done >= total)) {
            *lastEmit = now;
            std::cout << "PROGRESS " << done << " " << total << " " << found << "\n" << std::flush;
        }
    };
}

} // namespace

int main() {
    std::ios::sync_with_stdio(false);
    std::cin.tie(nullptr);

    auto reader = std::make_shared<CrossProcessReader>();
    SearchEngine scanner(reader, "/data/local/tmp");

    std::cout << "READY\n" << std::flush;

    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;

        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;

        if (cmd == "PING") {
            std::cout << "PONG\n" << std::flush;
        } else if (cmd == "QUIT" || cmd == "EXIT") {
            std::cout << "BYE\n" << std::flush;
            break;
        } else if (cmd == "SCAN_PROCS") {
            bool includeSys = true;
            iss >> includeSys;
            auto res = ProcessScanner::scanProcesses(includeSys);
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
                continue;
            }
            std::cout << "OK " << res.value.size() << "\n";
            for (const auto& p : res.value) {
                std::cout << p.pid << "\t"
                          << p.uid << "\t"
                          << (p.is64Bit ? 1 : 0) << "\t"
                          << (p.isSystemApp ? 1 : 0) << "\t"
                          << p.rssBytes << "\t"
                          << p.packageName << "\t"
                          << p.cmdline << "\n";
            }
            std::cout << "END\n" << std::flush;
        } else if (cmd == "ATTACH") {
            int pid = -1;
            iss >> pid;
            auto st = reader->attach(static_cast<pid_t>(pid));
            if (st == StatusCode::OK) {
                scanner.clearResults();
                std::cout << "OK " << pid << "\n" << std::flush;
            } else {
                std::cout << "ERR " << static_cast<int>(st) << "\n" << std::flush;
            }
        } else if (cmd == "MAPS") {
            int pid = -1;
            uint32_t mask = 0xFFFFFFFFu;
            iss >> pid;
            if (!(iss >> mask)) mask = 0xFFFFFFFFu;
            if (pid <= 0) pid = reader->targetPid();
            auto res = MapsParser::parse(static_cast<pid_t>(pid), mask);
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
                continue;
            }
            std::cout << "OK " << res.value.size() << "\n";
            for (const auto& r : res.value) {
                std::string perms;
                perms.push_back(r.readable ? 'r' : '-');
                perms.push_back(r.writable ? 'w' : '-');
                perms.push_back(r.executable ? 'x' : '-');
                perms.push_back(r.isPrivate ? 'p' : 's');
                std::cout << std::hex << r.start << "\t" << r.end << std::dec << "\t"
                          << perms << "\t"
                          << regionFlagToString(r.regionFlag) << "\t"
                          << (r.path.empty() ? "[anon]" : r.path) << "\n";
            }
            std::cout << "END\n" << std::flush;
        } else if (cmd == "READ") {
            uint64_t addr = 0;
            size_t len = 0;
            iss >> std::hex >> addr >> std::dec >> len;
            if (len == 0 || len > 16 * 1024 * 1024) {
                std::cout << "ERR invalid_length\n" << std::flush;
                continue;
            }
            std::vector<uint8_t> buf(len, 0);
            auto res = reader->read(addr, buf.data(), len);
            if (!res.isOk() || res.value == 0) {
                std::cout << "ERR read_failed\n" << std::flush;
                continue;
            }
            std::cout << "OK " << toHex(buf.data(), res.value) << "\n" << std::flush;
        } else if (cmd == "BATCH_READ") {
            size_t count = 0;
            iss >> count;
            if (count == 0 || count > 8192) {
                std::cout << "ERR invalid_batch_count\n" << std::flush;
                continue;
            }
            std::cout << "OK " << count << "\n";
            uint8_t smallBuf[16]{};
            for (size_t i = 0; i < count; ++i) {
                std::string tok;
                if (!(iss >> tok)) break;
                size_t colon = tok.find(':');
                if (colon == std::string::npos) continue;
                uint64_t addr = std::strtoull(tok.substr(0, colon).c_str(), nullptr, 16);
                size_t sz = static_cast<size_t>(std::strtoul(tok.substr(colon + 1).c_str(), nullptr, 10));
                if (sz == 0 || sz > 8) sz = 4;
                std::memset(smallBuf, 0, sizeof(smallBuf));
                auto rd = reader->read(addr, smallBuf, sz);
                if (rd.isOk() && rd.value >= sz) {
                    uint64_t v = 0;
                    for (size_t b = 0; b < sz; ++b) {
                        v |= (static_cast<uint64_t>(smallBuf[b]) << (b * 8));
                    }
                    std::cout << std::hex << addr << "\t" << std::dec << static_cast<int64_t>(v) << "\n";
                }
            }
            std::cout << "END\n" << std::flush;
        } else if (cmd == "WRITE") {
            uint64_t addr = 0;
            std::string hexBytes;
            iss >> std::hex >> addr >> hexBytes;
            auto bytes = fromHex(hexBytes);
            if (bytes.empty()) {
                std::cout << "ERR empty_payload\n" << std::flush;
                continue;
            }
            auto res = reader->write(addr, bytes.data(), bytes.size());
            if (!res.isOk()) {
                std::cout << "ERR write_failed\n" << std::flush;
                continue;
            }
            std::cout << "OK " << res.value << "\n" << std::flush;
        } else if (cmd == "SET_CONFIG") {
            uint32_t mask = DEFAULT_SCAN_REGION_MASK;
            uint64_t startAddr = 0;
            uint64_t endAddr = UINT64_MAX;
            iss >> mask >> std::hex >> startAddr >> endAddr;
            scanner.setRegionMask(mask);
            scanner.setAddressRange(startAddr, endAddr);
            std::cout << "OK\n" << std::flush;
        } else if (cmd == "CLEAR") {
            scanner.clearResults();
            std::cout << "OK\n" << std::flush;
        } else if (cmd == "SEARCH_EXACT") {
            int ggType = 4;
            int64_t rawVal = 0;
            int aligned = 1;
            iss >> ggType >> rawVal >> aligned;
            DataType dt = mapGgType(ggType);
            auto res = scanner.searchExact(makeScanValue(dt, rawVal), dt, aligned != 0, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "REFINE_EXACT") {
            int ggType = 4;
            int64_t rawVal = 0;
            int opCode = 0;
            iss >> ggType >> rawVal >> opCode;
            DataType dt = mapGgType(ggType);
            CompareOp op = static_cast<CompareOp>(opCode);
            auto res = scanner.refineExact(makeScanValue(dt, rawVal), op, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "SEARCH_RANGE") {
            int ggType = 4;
            int64_t minRaw = 0, maxRaw = 0;
            int aligned = 1;
            iss >> ggType >> minRaw >> maxRaw >> aligned;
            DataType dt = mapGgType(ggType);
            auto res = scanner.searchRange(makeScanValue(dt, minRaw), makeScanValue(dt, maxRaw),
                                           dt, aligned != 0, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "REFINE_RANGE") {
            int ggType = 4;
            int64_t minRaw = 0, maxRaw = 0;
            iss >> ggType >> minRaw >> maxRaw;
            DataType dt = mapGgType(ggType);
            auto res = scanner.refineRange(makeScanValue(dt, minRaw), makeScanValue(dt, maxRaw),
                                           makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "FUZZY_INIT") {
            int ggType = 4;
            int aligned = 1;
            iss >> ggType >> aligned;
            DataType dt = mapGgType(ggType);
            auto res = scanner.startUnknownSnapshot(dt, aligned != 0, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "FUZZY_REFINE") {
            int condCode = 0;
            int64_t deltaRaw = 0;
            iss >> condCode >> deltaRaw;
            DataType dt = scanner.currentDataType();
            auto res = scanner.refineFuzzy(static_cast<FuzzyCondition>(condCode),
                                           makeScanValue(dt, deltaRaw), makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "SEARCH_GROUP") {
            uint32_t radius = 512;
            int ordered = 0;
            size_t itemCount = 0;
            iss >> radius >> ordered >> itemCount;
            GroupSearchQuery q;
            q.maxRadiusBytes = radius;
            q.ordered = (ordered != 0);
            for (size_t i = 0; i < itemCount; ++i) {
                int ggType = 4;
                int64_t rawBits = 0;
                iss >> ggType >> rawBits;
                DataType dt = mapGgType(ggType);
                q.items.push_back(GroupValueItem{makeScanValue(dt, rawBits), dt, 0});
            }
            auto res = scanner.searchGroup(q, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "SEARCH_XOR") {
            int ggType = 4;
            int64_t plainVal = 0;
            uint64_t xorKey = 0;
            uint32_t keyOff = 4;
            iss >> ggType >> plainVal >> xorKey >> keyOff;
            DataType dt = mapGgType(ggType);
            auto res = scanner.searchEncrypted(plainVal, dt, xorKey, keyOff, makeProgressStream());
            if (!res.isOk()) {
                std::cout << "ERR " << res.message << "\n" << std::flush;
            } else {
                std::cout << "OK " << scanner.totalMatchedCount() << "\n" << std::flush;
            }
        } else if (cmd == "GET_CANDIDATES") {
            size_t offset = 0;
            size_t limit = 2000;
            iss >> offset >> limit;
            std::vector<CandidateEntry> slice;
            scanner.fetchCandidates(offset, limit, slice);
            std::cout << "OK " << slice.size() << " " << scanner.totalMatchedCount() << "\n";
            for (const auto& c : slice) {
                int64_t bits = extractRawBits(c.previousValue, c.type);
                std::cout << std::hex << c.address << std::dec << "\t"
                          << bits << "\t"
                          << unmapGgType(c.type) << "\t"
                          << regionFlagToString(c.regionFlag) << "\n";
            }
            std::cout << "END\n" << std::flush;
        } else {
            std::cout << "ERR unknown_command\n" << std::flush;
        }
    }

    return 0;
}
