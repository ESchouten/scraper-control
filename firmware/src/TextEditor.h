// Typing text (a wifi password) with three buttons, no display code (tested on a PC).
// One ring of slots: OK, delete, the other character sets, then the characters of the current set.
//   left / right   previous / next slot (wraps; held = fast)
//   middle         use the slot: add the character, delete the last one, switch set, or done
// A new set starts on its first character, so "a", "A" and "0" are one press away.
#pragma once
#include <string>
#include <vector>

class TextEditor {
 public:
  enum class Slot { Done, Delete, Set, Char };
  static constexpr int kSets = 4;
  static constexpr size_t kMax = 63;   // longest WPA password

  explicit TextEditor(std::string start = "") : text_(std::move(start)) { pos_ = firstChar(); }

  const std::string& text() const { return text_; }
  int set() const { return set_; }
  int size() const { return int(kSpecials + kSets - 1 + chars().size()); }
  int cursor() const { return pos_; }

  Slot slot(int i) const {
    if (i == 0) return Slot::Done;
    if (i == 1) return Slot::Delete;
    if (i < firstChar()) return Slot::Set;
    return Slot::Char;
  }
  // What a slot shows: "OK", "<-" (delete), "ABC" / "abc" / "123" / "#+=" or the character.
  std::string label(int i) const {
    switch (slot(i)) {
      case Slot::Done: return "OK";
      case Slot::Delete: return "<-";
      case Slot::Set: return kSetNames[setAt(i)];
      default: {
        char c = chars()[i - firstChar()];
        return std::string(1, c);   // a space is " ": the screen writes it as a word
      }
    }
  }

  void next() { pos_ = (pos_ + 1) % size(); }
  void prev() { pos_ = (pos_ + size() - 1) % size(); }

  // Returns true when done (OK).
  bool press() {
    switch (slot(pos_)) {
      case Slot::Done: return true;
      case Slot::Delete:
        if (!text_.empty()) text_.pop_back();
        break;
      case Slot::Set:
        set_ = setAt(pos_);
        pos_ = firstChar();
        break;
      case Slot::Char:
        if (text_.size() < kMax) text_ += chars()[pos_ - firstChar()];
        break;
    }
    return false;
  }

 private:
  static constexpr int kSpecials = 2;   // OK, delete
  static constexpr const char* kSetNames[kSets] = {"abc", "ABC", "123", "#+="};
  static constexpr const char* kSetChars[kSets] = {
      "abcdefghijklmnopqrstuvwxyz",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      "0123456789",
      " !@#$%&*-_+=.,:;?/()[]{}<>'\"~^|\\`",
  };

  std::string chars() const { return kSetChars[set_]; }
  int firstChar() const { return kSpecials + kSets - 1; }
  // The set switches are the other sets, in order.
  int setAt(int i) const {
    int k = i - kSpecials;
    return k < set_ ? k : k + 1;
  }

  std::string text_;
  int set_ = 0;
  int pos_ = 0;
};
