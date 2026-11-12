// Host test for the update manifest (update.txt): one block per board, what is signed, newer-only.
// The same text as firmware/sign_release.py writes; test_cpp.py checks both agree.
#include <cassert>
#include <cstdio>
#include <iostream>

#include "../src/HttpLite.h"
#include "../src/Manifest.h"

int main(int argc, char** argv) {
  if (argc > 1) {   // print the signed message of a board's block in a manifest on stdin (test_firmware.py)
    std::string all, line;
    while (std::getline(std::cin, line)) all += line + "\n";
    std::cout << signedMessage(parseManifest(all, argv[1])) << "\n";
    return 0;
  }
  std::string text =
      "# Scraper Control updates\n"
      "[other-board]\nfile=other.bin\nversion=9.9.9\nbuild=999\nsize=10\n"
      "sha256=0000000000000000000000000000000000000000000000000000000000000000\nsig=30\n"
      "[m5stack-core]\r\nfile=scraper-control-m5stack-core-0.7.0.bin\r\nversion=0.7.0\nbuild=412\nsize=2145195\n"
      "sha256=c34ff75f3ac6de788e2984b5f1cd9be223f472fcb41e75bf3d2868940177a850\nsig=3045022100a9\n";
  Manifest m = parseManifest(text, "m5stack-core");
  assert(m.complete());
  assert(m.board == "m5stack-core" && m.version == "0.7.0" && m.build == 412 && m.size == 2145195);
  assert(m.file == "scraper-control-m5stack-core-0.7.0.bin");   // its own block, not the other board's
  assert(signedMessage(m) == "scraper-knop|m5stack-core|0.7.0|412|2145195|c34ff75f3ac6de788e2984b5f1cd9be223f472fcb41e75bf3d2868940177a850");
  assert(isUpdate(m, "m5stack-core", 411));
  assert(!isUpdate(m, "m5stack-core", 412));     // same build: nothing to do
  assert(!isUpdate(m, "m5stack-core2", 1));      // other board
  assert(!parseManifest(text, "m5stack-core2").complete());   // no block for this board: no update
  Manifest broken = parseManifest("[m5stack-core]\nbuild=500\n", "m5stack-core");
  assert(!broken.complete() && !isUpdate(broken, "m5stack-core", 1));
  assert(!parseManifest("board=m5stack-core\nfile=a.bin\nversion=1\nbuild=5\nsize=1\nsig=30\n", "m5stack-core").complete());   // old firmware.txt: not read
  for (const char* bad : {"../x.bin", "a/b.bin", ".hidden", "x.bin?y=1", "https:", ""}) {
    Manifest f = m;
    f.file = bad;
    assert(!f.complete());   // only a plain file name next to update.txt
  }
  std::vector<uint8_t> b;
  assert(fromHex("30450a", b) && b.size() == 3 && b[0] == 0x30 && b[2] == 0x0a);
  assert(!fromHex("3g", b) && !fromHex("123", b));
  assert(toHex(b.data(), 0).empty());
  uint8_t x[2] = {0xab, 0x01};
  assert(toHex(x, 2) == "ab01");
  {  // the small HTTPS GET: URL, status line, headers
    std::string host, path, v;
    assert(parseUrl("https://github.com/a/b/releases/latest/download/firmware.txt", host, path));
    assert(host == "github.com" && path == "/a/b/releases/latest/download/firmware.txt");
    assert(parseUrl("https://example.org", host, path) && host == "example.org" && path == "/");
    assert(!parseUrl("http://github.com/x", host, path));
    assert(statusCode("HTTP/1.1 302 Found") == 302 && statusCode("HTTP/1.0 200 OK") == 200 && statusCode("garbage") == 0);
    assert(isRedirect(302) && !isRedirect(200));
    assert(headerValue("Location: https://objects.example/x?y=1", "location", v) && v == "https://objects.example/x?y=1");
    assert(headerValue("content-length:2145195", "Content-Length", v) && std::atol(v.c_str()) == 2145195);
    assert(!headerValue("Content-Type: text/plain", "Content-Length", v));
  }
  std::puts("ok   update-manifest");
  return 0;
}
