// Host test for the M5Stack text editor (wifi password), no hardware needed.
#include <cassert>
#include <cstdio>

#include "../src/TextEditor.h"

static void go(TextEditor& e, const std::string& label) {   // walk right to a slot
  for (int i = 0; i < e.size(); i++) {
    if (e.label(e.cursor()) == label) return;
    e.next();
  }
  assert(!"slot not found");
}

int main() {
  {  // starts on 'a' of the lower-case set; OK and delete are just to the left
    TextEditor e;
    assert(e.label(e.cursor()) == "a" && e.set() == 0);
    e.prev(); e.prev(); e.prev(); e.prev();
    assert(e.label(e.cursor()) == "<-");
    e.prev();
    assert(e.label(e.cursor()) == "OK");
    e.prev();   // wraps to the last character
    assert(e.label(e.cursor()) == "z");
  }
  {  // type "Ab1!" across all four sets
    TextEditor e;
    go(e, "ABC"); assert(!e.press()); assert(e.set() == 1 && e.label(e.cursor()) == "A");
    e.press();
    go(e, "abc"); e.press(); e.next(); e.press();                 // b
    go(e, "123"); e.press(); e.next(); e.press();                 // 1
    go(e, "#+="); e.press(); e.next(); e.press();                 // ! (after the space)
    assert(e.text() == "Ab1!");
    go(e, "<-"); e.press();
    assert(e.text() == "Ab1");
    go(e, "OK");
    assert(e.press());
  }
  {  // the symbols start with the space; the set switches never list the current set
    TextEditor e("x");
    go(e, "#+="); e.press();
    assert(e.label(e.cursor()) == " ");
    for (int i = 0; i < e.size(); i++) assert(e.label(i) != "#+=");
    e.press();
    assert(e.text() == "x ");
  }
  {  // at most 63 characters
    TextEditor e;
    for (int i = 0; i < 80; i++) e.press();
    assert(e.text().size() == TextEditor::kMax);
  }
  std::puts("ok   text editor (wifi password)");
  return 0;
}
