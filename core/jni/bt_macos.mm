// Classic Bluetooth serial on macOS (IOBluetooth): an inquiry plus the devices the Mac already
// knows, and an RFCOMM channel to the service's channel (found by SDP), without pairing.
// IOBluetooth calls back on the run loop of the thread that started the work, so all of it runs
// on one thread of our own with a running run loop; the calls below hand work to that thread
// and wait for its answer.
#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>

#include <condition_variable>
#include <deque>
#include <map>
#include <memory>
#include <mutex>

#include "bt_serial.h"

namespace {
struct Link {   // one open channel: what came in, and whether it closed
  std::mutex mu;
  std::condition_variable cv;
  std::deque<uint8_t> in;
  bool closed = false;
  IOBluetoothRFCOMMChannel* channel = nil;
  IOBluetoothDevice* device = nil;
  id owner = nil;   // the channel's delegate, kept alive until close
};
std::mutex linksMu;
std::map<int64_t, std::shared_ptr<Link>> links;
int64_t nextHandle = 1;

std::string text(NSString* s) { return s ? std::string(s.UTF8String) : std::string(); }

// "00-12-ff-..." (IOBluetooth) <-> "00:12:ff:..." (ours)
std::string ours(NSString* a) {
  std::string s = text(a);
  for (auto& c : s) c = c == '-' ? ':' : (char)tolower((unsigned char)c);
  return s;
}
NSString* theirs(const std::string& a) {
  std::string s = a;
  for (auto& c : s) if (c == ':') c = '-';
  return [NSString stringWithUTF8String:s.c_str()];
}

// A small waiter: the Bluetooth thread says done, the caller waits for it.
struct Done {
  std::mutex mu;
  std::condition_variable cv;
  bool done = false;
  int status = 0;
  void set(int s) {
    std::lock_guard<std::mutex> g(mu);
    done = true;
    status = s;
    cv.notify_all();
  }
  bool wait(int ms) {
    std::unique_lock<std::mutex> g(mu);
    return cv.wait_for(g, std::chrono::milliseconds(ms), [&] { return done; });
  }
};
}  // namespace

@interface ScraperBt : NSObject <IOBluetoothDeviceInquiryDelegate, IOBluetoothRFCOMMChannelDelegate>
@end

@implementation ScraperBt {
@public
  std::mutex foundMu;
  std::map<std::string, std::pair<std::string, int>> found;   // address -> name, rssi
  std::string prefix;
  Done* sdpDone;
  Done* openDone;
  std::shared_ptr<Link> link;
}

- (void)run:(id)block {
  ((void (^)(void))block)();
}

- (void)note:(IOBluetoothDevice*)d {
  std::string a = ours(d.addressString);
  if (a.rfind(prefix, 0) != 0) return;
  std::lock_guard<std::mutex> g(foundMu);
  found[a] = {text(d.name), d.RSSI == 127 ? -127 : (int)d.RSSI};
}

- (void)deviceInquiryDeviceFound:(IOBluetoothDeviceInquiry*)sender device:(IOBluetoothDevice*)device {
  [self note:device];
}

- (void)deviceInquiryDeviceNameUpdated:(IOBluetoothDeviceInquiry*)sender device:(IOBluetoothDevice*)device devicesRemaining:(uint32_t)n {
  [self note:device];
}

- (void)sdpQueryComplete:(IOBluetoothDevice*)device status:(IOReturn)status {
  if (sdpDone) sdpDone->set(status);
}

- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel*)channel status:(IOReturn)error {
  if (openDone) openDone->set(error);
}

- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel*)channel data:(void*)data length:(size_t)length {
  auto l = link;
  if (!l) return;
  std::lock_guard<std::mutex> g(l->mu);
  l->in.insert(l->in.end(), (uint8_t*)data, (uint8_t*)data + length);
  l->cv.notify_all();
}

- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel*)channel {
  auto l = link;
  if (!l) return;
  std::lock_guard<std::mutex> g(l->mu);
  l->closed = true;
  l->cv.notify_all();
}
@end

namespace {
NSThread* btThread() {   // started once; runs its run loop for good
  static NSThread* t = [] {
    NSThread* th = [[NSThread alloc] initWithBlock:^{
      NSRunLoop* rl = [NSRunLoop currentRunLoop];
      [rl addPort:[NSMachPort port] forMode:NSDefaultRunLoopMode];   // keeps the loop running
      for (;;) @autoreleasepool {
          [rl runMode:NSDefaultRunLoopMode beforeDate:[NSDate distantFuture]];
        }
    }];
    th.name = @"scraper-bt";
    [th start];
    return th;
  }();
  return t;
}

void onBt(ScraperBt* target, void (^block)(void)) {   // runs block on the Bluetooth thread, waits
  [target performSelector:@selector(run:) onThread:btThread() withObject:[block copy] waitUntilDone:YES];
}
}  // namespace

namespace btserial {

bool supported() { return IOBluetoothHostController.defaultController != nil; }

std::string find(const std::string& prefix, int ms) {
  ScraperBt* f = [ScraperBt new];
  f->prefix = prefix;
  __block IOBluetoothDeviceInquiry* inquiry = nil;
  onBt(f, ^{
    for (IOBluetoothDevice* d in [IOBluetoothDevice pairedDevices]) [f note:d];   // known: no inquiry needed
    inquiry = [IOBluetoothDeviceInquiry inquiryWithDelegate:f];
    inquiry.inquiryLength = (uint8_t)(ms < 1000 ? 1 : ms > 48000 ? 48 : ms / 1000);
    inquiry.updateNewDeviceNames = YES;
    [inquiry start];
  });
  [NSThread sleepForTimeInterval:ms / 1000.0];
  onBt(f, ^{
    [inquiry stop];
    inquiry.delegate = nil;   // no late callbacks into f
  });
  std::string out = "[";
  std::lock_guard<std::mutex> g(f->foundMu);
  for (auto& [a, v] : f->found) {
    if (out.size() > 1) out += ",";
    out += "{\"address\":" + jsonString(a) + ",\"name\":" + jsonString(v.first) + ",\"rssi\":" + std::to_string(v.second) + "}";
  }
  return out + "]";
}

int64_t open(const std::string& address, const std::string& service, std::string& error) {
  uint8_t u[16];
  uint64_t bits;
  if (!parseAddress(address, bits) || !parseUuid(service, u)) {
    error = "bad address or service";
    return -1;
  }
  auto l = std::make_shared<Link>();
  ScraperBt* c = [ScraperBt new];
  c->link = l;
  Done sdp, opened;
  c->sdpDone = &sdp;
  c->openDone = &opened;
  IOBluetoothSDPUUID* uuid = [IOBluetoothSDPUUID uuidWithBytes:u length:16];
  __block IOBluetoothDevice* dev = nil;
  __block IOReturn r = kIOReturnSuccess;
  onBt(c, ^{
    dev = [IOBluetoothDevice deviceWithAddressString:theirs(address)];
    r = dev ? [dev performSDPQuery:c uuids:@[ uuid ]] : kIOReturnNotFound;
  });
  // callbacks run on the Bluetooth thread: after this, none touches sdp / opened any more
  auto forget = ^{
    onBt(c, ^{
      c->sdpDone = nullptr;
      c->openDone = nullptr;
    });
  };
  if (r != kIOReturnSuccess || !sdp.wait(15000)) {
    forget();
    error = "service search failed";
    return -1;
  }
  __block BluetoothRFCOMMChannelID ch = 0;
  __block IOBluetoothRFCOMMChannel* chan = nil;
  onBt(c, ^{
    IOBluetoothSDPServiceRecord* rec = [dev getServiceRecordForUUID:uuid];
    if (!rec || [rec getRFCOMMChannelID:&ch] != kIOReturnSuccess) {
      r = kIOReturnNotFound;
      return;
    }
    r = [dev openRFCOMMChannelAsync:&chan withChannelID:ch delegate:c];
  });
  if (r != kIOReturnSuccess) {
    forget();
    error = r == kIOReturnNotFound ? "the robot has no such service" : "could not open the channel";
    return -1;
  }
  bool ok = opened.wait(15000) && opened.status == kIOReturnSuccess;
  forget();
  if (!ok) {
    onBt(c, ^{
      [chan closeChannel];
      [dev closeConnection];
    });
    error = "the channel did not open";
    return -1;
  }
  l->channel = chan;
  l->device = dev;
  l->owner = c;
  std::lock_guard<std::mutex> g(linksMu);
  links[nextHandle] = l;
  return nextHandle++;
}

static std::shared_ptr<Link> get(int64_t h) {
  std::lock_guard<std::mutex> g(linksMu);
  auto it = links.find(h);
  return it == links.end() ? nullptr : it->second;
}

int read(int64_t h, uint8_t* buf, int n) {
  auto l = get(h);
  if (!l) return -1;
  std::unique_lock<std::mutex> g(l->mu);
  l->cv.wait(g, [&] { return !l->in.empty() || l->closed; });
  if (l->in.empty()) return -1;
  int k = 0;
  while (k < n && !l->in.empty()) {
    buf[k++] = l->in.front();
    l->in.pop_front();
  }
  return k;
}

bool write(int64_t h, const uint8_t* data, int n) {
  auto l = get(h);
  if (!l || l->closed) return false;
  __block bool ok = true;
  ScraperBt* runner = [ScraperBt new];
  onBt(runner, ^{
    int mtu = l->channel.getMTU > 0 ? l->channel.getMTU : 127;
    for (int at = 0; ok && at < n; at += mtu) {
      int part = n - at < mtu ? n - at : mtu;
      ok = [l->channel writeSync:(void*)(data + at) length:(UInt16)part] == kIOReturnSuccess;
    }
  });
  return ok;
}

void close(int64_t h) {
  std::shared_ptr<Link> l;
  {
    std::lock_guard<std::mutex> g(linksMu);
    auto it = links.find(h);
    if (it == links.end()) return;
    l = it->second;
    links.erase(it);
  }
  ScraperBt* runner = [ScraperBt new];
  onBt(runner, ^{
    [l->channel closeChannel];
    [l->device closeConnection];
    l->owner = nil;   // the delegate goes (it holds this link too)
  });
  std::lock_guard<std::mutex> g(l->mu);
  l->closed = true;
  l->cv.notify_all();
}

}  // namespace btserial
