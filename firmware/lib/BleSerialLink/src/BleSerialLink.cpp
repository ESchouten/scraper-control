#ifdef ARDUINO
#include "BleSerialLink.h"

#include <algorithm>
#include <cctype>

namespace {
const char* kService = driver::kBle.service;
const char* kRx = driver::kBle.rx;   // write to robot
const char* kTx = driver::kBle.tx;   // notifications from robot
const std::string kPrefix = driver::kBle.addressPrefix;

bool prefixed(const std::string& a) { return !kPrefix.empty() && a.rfind(kPrefix, 0) == 0; }

std::string lower(std::string s) {
  for (auto& c : s) c = (char)tolower((unsigned char)c);
  return s;
}

void startBle() {
  if (NimBLEDevice::isInitialized()) return;
  NimBLEDevice::init("scraper-control");
  NimBLEDevice::setMTU(247);
}
}  // namespace

BleSerialLink::BleSerialLink(std::string address) : address_(lower(address)) {}

bool BleSerialLink::scan() {   // the strongest robot nearby
  std::vector<FoundRobot> found = scanAll();
  if (found.empty()) return false;
  address_ = found.front().address;
  return true;
}

std::vector<FoundRobot> BleSerialLink::scanAll(uint32_t ms) {
  startBle();
  NimBLEScan* s = NimBLEDevice::getScan();
  s->setActiveScan(true);
  NimBLEScanResults res = s->getResults(ms, false);
  std::vector<FoundRobot> out;
  for (int i = 0; i < res.getCount(); i++) {
    const NimBLEAdvertisedDevice* d = res.getDevice(i);
    std::string a = lower(d->getAddress().toString());
    if (!prefixed(a) && !d->isAdvertisingService(NimBLEUUID(kService))) continue;
    out.push_back({a, d->getName(), d->getRSSI()});
  }
  s->clearResults();
  std::sort(out.begin(), out.end(), [](const FoundRobot& x, const FoundRobot& y) { return x.rssi > y.rssi; });
  return out;
}

bool BleSerialLink::connect() {
  startBle();
  if (address_.empty() && !scan()) return false;
  if (!client_) client_ = NimBLEDevice::createClient();
  client_->setConnectTimeout(5000);
  if (!client_->connect(NimBLEAddress(address_, BLE_ADDR_PUBLIC))) {
    // Unknown address type or robot moved: scan once and retry.
    std::string keep = address_;
    address_.clear();
    if (!scan() || !client_->connect(NimBLEAddress(address_, BLE_ADDR_PUBLIC))) { address_ = keep; return false; }
  }
  NimBLERemoteService* svc = client_->getService(kService);
  if (!svc) { client_->disconnect(); return false; }
  rx_ = svc->getCharacteristic(kRx);
  NimBLERemoteCharacteristic* tx = svc->getCharacteristic(kTx);
  if (!rx_ || !tx) { client_->disconnect(); return false; }
  {
    std::lock_guard<std::mutex> g(mu_);
    inbox_.clear();
  }
  return tx->subscribe(true, [this](NimBLERemoteCharacteristic*, uint8_t* d, size_t n, bool) { onNotify(d, n); });
}

void BleSerialLink::onNotify(const uint8_t* d, size_t n) {   // NimBLE task
  std::lock_guard<std::mutex> g(mu_);
  inbox_.insert(inbox_.end(), d, d + n);
}

bool BleSerialLink::write(const driver::Bytes& frame) {
  return rx_ && client_ && client_->isConnected() && rx_->writeValue(frame.data(), frame.size(), false);
}

void BleSerialLink::disconnect() {
  if (client_ && client_->isConnected()) client_->disconnect();
  rx_ = nullptr;
}

void BleSerialLink::pump(uint32_t ms) {   // main task: deliver what arrived
  uint32_t end = millis() + ms;
  do {
    driver::Bytes chunk;
    {
      std::lock_guard<std::mutex> g(mu_);
      chunk.swap(inbox_);
    }
    if (!chunk.empty() && onData) onData(chunk.data(), chunk.size());
    else delay(5);
  } while ((int32_t)(end - millis()) > 0);
}

#endif
