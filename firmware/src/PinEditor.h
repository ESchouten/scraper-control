// PIN entry with three buttons, no display code (so it can be tested on a PC).
// Cursor walks over the digits and then an "OK" slot:
//   on a digit:  minus/plus change it (0-9, wraps), next moves on
//   on OK:       plus adds a digit, minus removes the last one, next confirms
#pragma once
#include <string>

class PinEditor {
 public:
  static constexpr size_t kMax = 8;

  explicit PinEditor(std::string start) : digits_(start.empty() ? "0000" : start) {}

  const std::string& digits() const { return digits_; }
  size_t cursor() const { return pos_; }                 // == digits().size() means OK
  bool onOk() const { return pos_ == digits_.size(); }

  void plus() {
    if (onOk()) {
      if (digits_.size() < kMax) { digits_ += '0'; pos_ = digits_.size() - 1; }
    } else {
      char& c = digits_[pos_];
      c = c == '9' ? '0' : char(c + 1);
    }
  }

  void minus() {
    if (onOk()) {
      if (!digits_.empty()) { digits_.pop_back(); pos_ = digits_.size(); }
    } else {
      char& c = digits_[pos_];
      c = c == '0' ? '9' : char(c - 1);
    }
  }

  // Returns true when the PIN is confirmed (next pressed on OK).
  bool next() {
    if (onOk()) return true;
    pos_++;
    return false;
  }

 private:
  std::string digits_;
  size_t pos_ = 0;
};
