// Differential test adapter: links the production, allocation-free firmware parser.
#include "Protocol.h"
#include <array>
#include <iostream>
#include <string>
#include <vector>

int main() {
    std::array<uint8_t, Protocol::ArtLimit> storage{};
    Protocol::Receiver receiver(storage.data(), storage.size());
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "reset") { receiver.reset(); std::cout << "reset\n"; continue; }
        std::vector<uint8_t> frame;
        for (size_t index = 0; index + 1 < line.size(); index += 2)
            frame.push_back(static_cast<uint8_t>(std::stoul(line.substr(index, 2), nullptr, 16)));
        auto result = receiver.accept(frame.data(), frame.size());
        bool valid = result == Protocol::Result::Accepted || result == Protocol::Result::Complete;
        std::cout << valid << ' ' << (result == Protocol::Result::Complete) << ' '
                  << receiver.offset() << ' ' << Protocol::crc32(storage.data(), receiver.offset()) << '\n';
    }
}
