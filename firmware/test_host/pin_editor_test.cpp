// Host test for the M5Stack PIN editor (no hardware needed).
#include <cassert>
#include <cstdio>

#include "../src/PinEditor.h"

int main() {
  {  // change 0000 into 1290: digits wrap both ways
    PinEditor e("0000");
    e.plus();                       // 1
    assert(!e.next());
    e.plus(); e.plus();             // 2
    assert(!e.next());
    e.minus();                      // 9 (wraps)
    assert(!e.next());
    assert(!e.next());              // keep 0, now on OK
    assert(e.onOk());
    assert(e.next());
    assert(e.digits() == "1290");
  }
  {  // grow and shrink on the OK slot
    PinEditor e("12");
    e.next(); e.next();
    assert(e.onOk());
    e.plus();                       // adds a digit and jumps to it
    assert(e.digits() == "120" && e.cursor() == 2);
    e.plus(); e.plus(); e.plus();   // 3
    e.next();
    assert(e.onOk());
    e.minus(); e.minus();
    assert(e.digits() == "1" && e.onOk());
    for (int i = 0; i < 20; i++) { e.plus(); e.next(); }
    assert(e.digits().size() == PinEditor::kMax);
  }
  {  // empty start becomes 0000
    PinEditor e("");
    assert(e.digits() == "0000" && e.cursor() == 0);
  }
  std::puts("ok   PIN editor");
  return 0;
}
