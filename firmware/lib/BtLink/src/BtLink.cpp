#ifdef ARDUINO
#include "BtLink.h"

#include <BluetoothSerial.h>
#include <esp_gap_ble_api.h>

#include <algorithm>
#include <cctype>

namespace {
const char* kService = driver::kBle.service;
const char* kRx = driver::kBle.rx;   // write to robot
const char* kTx = driver::kBle.tx;   // notifications from robot
const std::string kPrefix = driver::kBle.addressPrefix;
const uint32_t kClassicSearchMs = 8000;   // an inquiry takes about 10 s in full

BluetoothSerial spp;   // the classic link (one robot at a time)
bool sppStarted = false;
BLEClient* client = nullptr;   // the BLE link's, kept for every session (BLEDevice owns it)
BtLink* notifyTo = nullptr;   // the BLE link that gets notifications

bool prefixed(const std::string& a) { return !kPrefix.empty() && a.rfind(kPrefix, 0) == 0; }

std::string lower(std::string s) {
  for (auto& c : s) c = (char)tolower((unsigned char)c);
  return s;
}

void startBle() {
  if (BLEDevice::getInitialized()) return;
  BLEDevice::init("scraper-control");
  BLEDevice::setMTU(247);
}

bool startClassic() {
  startBle();   // Bluedroid with BLE first; classic joins the same host
  if (!sppStarted) sppStarted = spp.begin("scraper-control", true);   // master: we connect out
  return sppStarted;
}

void sortByRssi(std::vector<FoundRobot>& v) {
  std::sort(v.begin(), v.end(), [](const FoundRobot& x, const FoundRobot& y) { return x.rssi > y.rssi; });
}
}  // namespace

BtLink::BtLink(std::string address, bool classic) : address_(lower(address)), classic_(classic) {}

void BtLink::release() {
  // BLE first, while the host still runs (its client unregisters cleanly); it stops the host and
  // the controller, false: so Bluetooth can start again. Then the classic side frees its tasks.
  if (BLEDevice::getInitialized()) BLEDevice::deinit(false);
  client = nullptr;   // deleted by deinit
  if (sppStarted) {
    spp.end();
    sppStarted = false;
  }
}

std::vector<FoundRobot> BtLink::scanAll(uint32_t ms) {
  std::vector<FoundRobot> out;
  startBle();
  BLEScan* s = BLEDevice::getScan();
  s->setActiveScan(true);
  BLEScanResults* res = s->start((ms + 999) / 1000, false);
  for (int i = 0; res && i < res->getCount(); i++) {
    BLEAdvertisedDevice d = res->getDevice(i);
    std::string a = lower(d.getAddress().toString().c_str());
    if (!prefixed(a) && !d.isAdvertisingService(BLEUUID(kService))) continue;
    out.push_back({a, d.getName().c_str(), d.getRSSI(), false});
  }
  s->clearResults();
  if (out.empty() && startClassic()) {
    BTScanResults* found = spp.discover(kClassicSearchMs);
    for (int i = 0; found && i < found->getCount(); i++) {
      BTAdvertisedDevice* d = found->getDevice(i);
      std::string a = lower(d->getAddress().toString().c_str());
      if (prefixed(a)) out.push_back({a, d->getName(), d->getRSSI(), true});
    }
    spp.discoverClear();
  }
  sortByRssi(out);
  return out;
}

bool BtLink::search() {   // the strongest robot nearby
  std::vector<FoundRobot> found = scanAll();
  if (found.empty()) return false;
  address_ = found.front().address;
  classic_ = found.front().classic;
  return true;
}

bool BtLink::connect() {
  if (address_.empty() && !search()) return false;
  {
    std::lock_guard<std::mutex> g(mu_);
    inbox_.clear();
  }
  // Bluetooth sometimes fails once and works the next time: three tries, a second apart.
  for (int i = 0; i < 3; i++) {
    if (i) delay(1000);
    if (classic_ ? connectClassic() : connectBle()) return true;
  }
  // Robot moved or answers another way now: search once and retry.
  std::string keepAddress = address_;
  bool keepClassic = classic_;
  address_.clear();
  if (search() && (classic_ ? connectClassic() : connectBle())) return true;
  address_ = keepAddress;
  classic_ = keepClassic;
  return false;
}

bool BtLink::connectBle() {
  startBle();
  if (!client) client = BLEDevice::createClient();
  // A short connection interval (7.5 to 15 ms; Bluedroid takes 30 to 50 ms by itself), so
  // every key and every answer of the robot crosses sooner. Supervision timeout 4 s.
  esp_bd_addr_t a;
  if (sscanf(address_.c_str(), "%hhx:%hhx:%hhx:%hhx:%hhx:%hhx", &a[0], &a[1], &a[2], &a[3], &a[4], &a[5]) == 6)
    esp_ble_gap_set_prefer_conn_params(a, 6, 12, 0, 400);
  if (!client->connect(BLEAddress(address_.c_str()), BLE_ADDR_TYPE_PUBLIC, 5000)) return false;
  BLERemoteService* svc = client->getService(BLEUUID(kService));
  if (!svc) { client->disconnect(); return false; }
  rx_ = svc->getCharacteristic(BLEUUID(kRx));
  BLERemoteCharacteristic* tx = svc->getCharacteristic(BLEUUID(kTx));
  if (!rx_ || !tx) { client->disconnect(); return false; }
  notifyTo = this;
  tx->registerForNotify([](BLERemoteCharacteristic*, uint8_t* d, size_t n, bool) { if (notifyTo) notifyTo->received(d, n); });
  return true;
}

bool BtLink::connectClassic() {
  if (!startClassic()) return false;
  return spp.connect(BTAddress(String(address_.c_str())), 0, ESP_SPP_SEC_NONE, ESP_SPP_ROLE_MASTER);
}

bool BtLink::linkUp() {
  return classic_ ? (sppStarted && spp.connected(0)) : (client && client->isConnected());
}

void BtLink::received(const uint8_t* d, size_t n) {   // Bluetooth task
  std::lock_guard<std::mutex> g(mu_);
  inbox_.insert(inbox_.end(), d, d + n);
}

bool BtLink::write(const driver::Bytes& frame) {
  if (classic_) return linkUp() && spp.write(frame.data(), frame.size()) == frame.size();
  if (!rx_ || !linkUp()) return false;
  return rx_->writeValue((uint8_t*)frame.data(), frame.size(), false);
}

void BtLink::disconnect() {
  if (classic_) {
    if (sppStarted && spp.connected(0)) spp.disconnect();
  } else if (client && client->isConnected()) {
    client->disconnect();
  }
  notifyTo = nullptr;
  rx_ = nullptr;
}

void BtLink::pump(uint32_t ms) {   // main task: deliver what arrived
  uint32_t end = millis() + ms;
  do {
    driver::Bytes chunk;
    if (classic_) {
      while (sppStarted && spp.available()) chunk.push_back((uint8_t)spp.read());
    } else {
      std::lock_guard<std::mutex> g(mu_);
      chunk.swap(inbox_);
    }
    if (!chunk.empty() && onData) onData(chunk.data(), chunk.size());
    else delay(5);
  } while ((int32_t)(end - millis()) > 0);
}

#endif
