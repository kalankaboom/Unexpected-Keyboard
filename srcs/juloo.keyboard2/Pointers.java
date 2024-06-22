package juloo.keyboard2;

import android.os.Handler;
import android.os.Message;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Manage pointers (fingers) on the screen and long presses.
 * Call back to IPointerEventHandler.
 */
public final class Pointers implements Handler.Callback
{
  public static final int FLAG_P_LATCHABLE = 1;
  public static final int FLAG_P_LATCHED = (1 << 1);
  public static final int FLAG_P_FAKE = (1 << 2);
  public static final int FLAG_P_LOCKABLE = (1 << 3);
  public static final int FLAG_P_LOCKED = (1 << 4);
  public static final int FLAG_P_SLIDING = (1 << 5);
  /** Clear latched (only if also FLAG_P_LATCHABLE set). */
  public static final int FLAG_P_CLEAR_LATCHED = (1 << 6);
  /** Can't be locked, even when long pressing. */
  public static final int FLAG_P_CANT_LOCK = (1 << 7);

  private Handler _keyrepeat_handler;
  private ArrayList<Pointer> _ptrs = new ArrayList<Pointer>();
  private IPointerEventHandler _handler;
  private Config _config;

  public Pointers(IPointerEventHandler h, Config c)
  {
    _keyrepeat_handler = new Handler(this);
    _handler = h;
    _config = c;
  }

  /** Return the list of modifiers currently activated. */
  public Modifiers getModifiers()
  {
    return getModifiers(false);
  }

  /** When [skip_latched] is true, don't take flags of latched keys into account. */
  private Modifiers getModifiers(boolean skip_latched)
  {
    int n_ptrs = _ptrs.size();
    KeyValue[] mods = new KeyValue[n_ptrs];
    int n_mods = 0;
    for (int i = 0; i < n_ptrs; i++)
    {
      Pointer p = _ptrs.get(i);
      if (p.value != null
          && !(skip_latched && p.hasFlagsAny(FLAG_P_LATCHED)
            && (p.flags & FLAG_P_LOCKED) == 0))
        mods[n_mods++] = p.value;
    }
    return Modifiers.ofArray(mods, n_mods);
  }

  public void clear()
  {
    for (Pointer p : _ptrs)
      stopKeyRepeat(p);
    _ptrs.clear();
  }

  public boolean isKeyDown(KeyboardData.Key k)
  {
    for (Pointer p : _ptrs)
      if (p.key == k)
        return true;
    return false;
  }

  /** See [FLAG_P_*] flags. Returns [-1] if the key is not pressed. */
  public int getKeyFlags(KeyValue kv)
  {
    for (Pointer p : _ptrs)
      if (p.value != null && p.value.equals(kv))
        return p.flags;
    return -1;
  }

  /** The key must not be already latched . */
  void add_fake_pointer(KeyboardData.Key key, KeyValue kv, boolean locked)
  {
    Pointer ptr = new Pointer(-1, key, kv, 0.f, 0.f, Modifiers.EMPTY);
    ptr.flags = FLAG_P_FAKE | FLAG_P_LATCHED;
    if (locked)
      ptr.flags |= FLAG_P_LOCKED;
    _ptrs.add(ptr);
    _handler.onPointerFlagsChanged(false);
  }

  /** Set whether a key is latched or locked by adding a "fake" pointer, a
      pointer that is not due to user interaction.
      This is used by auto-capitalisation.

      When [lock] is true, [latched] control whether the modifier is locked or disabled.
      When [lock] is false, an existing locked pointer is not affected. */
  public void set_fake_pointer_state(KeyboardData.Key key, KeyValue kv,
      boolean latched, boolean lock)
  {
    Pointer ptr = getLatched(key, kv);
    if (ptr == null)
    {
      // No existing pointer, latch the key.
      if (latched)
        add_fake_pointer(key, kv, lock);
    }
    else if ((ptr.flags & FLAG_P_FAKE) != 0)
    {} // Key already latched but not by a fake ptr, do nothing.
    else if (lock)
    {
      // Acting on locked modifiers, replace the pointer each time.
      removePtr(ptr);
      if (latched)
        add_fake_pointer(key, kv, lock);
    }
    else if ((ptr.flags & FLAG_P_LOCKED) != 0)
    {} // Existing ptr is locked but [lock] is false, do not continue.
    else if (!latched)
    {
      // Key is latched by a fake ptr. Unlatch if requested.
      removePtr(ptr);
      _handler.onPointerFlagsChanged(false);
    }
  }

  // Receiving events

  public void onTouchUp(int pointerId)
  {
    Pointer ptr = getPtr(pointerId);
    if (ptr == null)
      return;
    if (ptr.hasFlagsAny(FLAG_P_SLIDING))
    {
      clearLatched();
      ptr.sliding.onTouchUp(ptr);
      return;
    }
    stopKeyRepeat(ptr);
    KeyValue ptr_value = ptr.value;
    if (ptr.gesture != null && ptr.gesture.is_in_progress())
    {
      // A gesture was in progress
      ptr.gesture.pointer_up();
    }
    Pointer latched = getLatched(ptr);
    if (latched != null) // Already latched
    {
      removePtr(ptr); // Remove dupplicate
      if ((latched.flags & FLAG_P_LOCKABLE) != 0) // Toggle lockable key
        lockPointer(latched, false);
      else // Otherwise, unlatch
      {
        removePtr(latched);
        _handler.onPointerUp(ptr_value, ptr.modifiers);
      }
    }
    else if ((ptr.flags & FLAG_P_LATCHABLE) != 0)
    {
      // Latchable but non-special keys must clear latched.
      if ((ptr.flags & FLAG_P_CLEAR_LATCHED) != 0)
        clearLatched();
      ptr.flags |= FLAG_P_LATCHED;
      ptr.pointerId = -1;
      _handler.onPointerFlagsChanged(false);
    }
    else
    {
      clearLatched();
      removePtr(ptr);
      _handler.onPointerUp(ptr_value, ptr.modifiers);
    }
  }

  public void onTouchCancel()
  {
    clear();
    _handler.onPointerFlagsChanged(true);
  }

  /* Whether an other pointer is down on a non-special key. */
  private boolean isOtherPointerDown()
  {
    for (Pointer p : _ptrs)
      if (!p.hasFlagsAny(FLAG_P_LATCHED) &&
          (p.value == null || !p.value.hasFlagsAny(KeyValue.FLAG_SPECIAL)))
        return true;
    return false;
  }

  public void onTouchDown(float x, float y, int pointerId, KeyboardData.Key key)
  {
    // Ignore new presses while a sliding key is active. On some devices, ghost
    // touch events can happen while the pointer travels on top of other keys.
    if (isSliding())
      return;
    // Don't take latched modifiers into account if an other key is pressed.
    // The other key already "own" the latched modifiers and will clear them.
    Modifiers mods = getModifiers(isOtherPointerDown());
    KeyValue value = _handler.modifyKey(key.keys[0], mods);
    Pointer ptr = new Pointer(pointerId, key, value, x, y, mods);
    _ptrs.add(ptr);
    startKeyRepeat(ptr);
    _handler.onPointerDown(value, false);
  }

  static final int[] DIRECTION_TO_INDEX = new int[]{
    7, 2, 2, 6, 6, 4, 4, 8, 8, 3, 3, 5, 5, 1, 1, 7
  };

  /**
   * [direction] is an int between [0] and [15] that represent 16 sections of a
   * circle, clockwise, starting at the top.
   */
  KeyValue getKeyAtDirection(KeyboardData.Key k, int direction)
  {
    return k.keys[DIRECTION_TO_INDEX[direction]];
  }

  /**
   * Get the key nearest to [direction] that is not key0. Take care
   * of applying [_handler.modifyKey] to the selected key in the same
   * operation to be sure to treat removed keys correctly.
   * Return [null] if no key could be found in the given direction or
   * if the selected key didn't change.
   */
  private KeyValue getNearestKeyAtDirection(Pointer ptr, int direction)
  {
    KeyValue k;
    // [i] is [0, -1, 1, -2, 2, ...]
    for (int i = 0; i > -4; i = (~i>>31) - i)
    {
      int d = (direction + i + 16) % 16;
      // Don't make the difference between a key that doesn't exist and a key
      // that is removed by [_handler]. Triggers side effects.
      k = _handler.modifyKey(getKeyAtDirection(ptr.key, d), ptr.modifiers);
      if (k != null)
        return k;
    }
    return null;
  }

  public void onTouchMove(float x, float y, int pointerId)
  {
    Pointer ptr = getPtr(pointerId);
    if (ptr == null)
      return;
    if (ptr.hasFlagsAny(FLAG_P_SLIDING))
    {
      ptr.sliding.onTouchMove(ptr, x);
      return;
    }

    // The position in a IME windows is clampled to view.
    // For a better up swipe behaviour, set the y position to a negative value when clamped.
    if (y == 0.0) y = -400;
    float dx = x - ptr.downX;
    float dy = y - ptr.downY;

    float dist = Math.abs(dx) + Math.abs(dy);
    if (dist < _config.swipe_dist_px)
    {
      // Pointer is still on the center.
      if (ptr.gesture == null || !ptr.gesture.is_in_progress())
        return;
      // Gesture ended
      ptr.gesture.moved_to_center();
      ptr.value = apply_gesture(ptr, ptr.gesture.get_gesture());
      ptr.flags = 0;

    }
    else
    { // Pointer is on a quadrant.
      // See [getKeyAtDirection()] for the meaning. The starting point on the
      // circle is the top direction.
      double a = Math.atan2(dy, dx) + Math.PI;
      // a is between 0 and 2pi, 0 is pointing to the left
      // add 12 to align 0 to the top
      int direction = ((int)(a * 8 / Math.PI) + 12) % 16;
      if (ptr.gesture == null)
      { // Gesture starts

        ptr.gesture = new Gesture(direction);
        KeyValue new_value = getNearestKeyAtDirection(ptr, direction);
        if (new_value != null)
        { // Pointer is swiping into a side key.

          ptr.value = new_value;
          ptr.flags = pointer_flags_of_kv(new_value);
          // Sliding mode is entered when key5 or key6 is down on a slider key.
          if (ptr.key.slider &&
              (new_value.equals(ptr.key.getKeyValue(5))
               || new_value.equals(ptr.key.getKeyValue(6))))
          {
            startSliding(ptr, x);
          }
          _handler.onPointerDown(new_value, true);
        }

      }
      else if (ptr.gesture.changed_direction(direction))
      { // Gesture changed state
        if (!ptr.gesture.is_in_progress())
        { // Gesture ended
          _handler.onPointerFlagsChanged(true);
        }
        else
        {
          ptr.value = apply_gesture(ptr, ptr.gesture.get_gesture());
          restartKeyRepeat(ptr);
          ptr.flags = 0; // Special behaviors are ignored during a gesture.
        }
      }
    }
  }

  // Pointers management

  private Pointer getPtr(int pointerId)
  {
    for (Pointer p : _ptrs)
      if (p.pointerId == pointerId)
        return p;
    return null;
  }

  private void removePtr(Pointer ptr)
  {
    _ptrs.remove(ptr);
  }

  private Pointer getLatched(Pointer target)
  {
    return getLatched(target.key, target.value);
  }

  private Pointer getLatched(KeyboardData.Key k, KeyValue v)
  {
    if (v == null)
      return null;
    for (Pointer p : _ptrs)
      if (p.key == k && p.hasFlagsAny(FLAG_P_LATCHED)
          && p.value != null && p.value.equals(v))
        return p;
    return null;
  }

  private void clearLatched()
  {
    for (int i = _ptrs.size() - 1; i >= 0; i--)
    {
      Pointer ptr = _ptrs.get(i);
      // Latched and not locked, remove
      if (ptr.hasFlagsAny(FLAG_P_LATCHED) && (ptr.flags & FLAG_P_LOCKED) == 0)
        _ptrs.remove(i);
      // Not latched but pressed, don't latch once released and stop long press.
      else if ((ptr.flags & FLAG_P_LATCHABLE) != 0)
        ptr.flags &= ~FLAG_P_LATCHABLE;
    }
  }

  /** Make a pointer into the locked state. */
  private void lockPointer(Pointer ptr, boolean shouldVibrate)
  {
    ptr.flags = (ptr.flags & ~FLAG_P_LOCKABLE) | FLAG_P_LOCKED;
    _handler.onPointerFlagsChanged(shouldVibrate);
  }

  boolean isSliding()
  {
    for (Pointer ptr : _ptrs)
      if (ptr.hasFlagsAny(FLAG_P_SLIDING))
        return true;
    return false;
  }

  // Key repeat

  /** Message from [_keyrepeat_handler]. */
  @Override
  public boolean handleMessage(Message msg)
  {
    for (Pointer ptr : _ptrs)
    {
      if (ptr.timeoutWhat == msg.what)
      {
        if (handleKeyRepeat(ptr))
          _keyrepeat_handler.sendEmptyMessageDelayed(msg.what,
              _config.longPressInterval);
        else
          ptr.timeoutWhat = -1;
        return true;
      }
    }
    return false;
  }

  private static int uniqueTimeoutWhat = 0;

  private void startKeyRepeat(Pointer ptr)
  {
    int what = (uniqueTimeoutWhat++);
    ptr.timeoutWhat = what;
    _keyrepeat_handler.sendEmptyMessageDelayed(what, _config.longPressTimeout);
  }

  private void stopKeyRepeat(Pointer ptr)
  {
    if (ptr.timeoutWhat != -1)
    {
      _keyrepeat_handler.removeMessages(ptr.timeoutWhat);
      ptr.timeoutWhat = -1;
    }
  }

  private void restartKeyRepeat(Pointer ptr)
  {
    stopKeyRepeat(ptr);
    startKeyRepeat(ptr);
  }

  /** A pointer is repeating. Returns [true] if repeat should continue. */
  private boolean handleKeyRepeat(Pointer ptr)
  {
    // Long press toggle lock on modifiers
    if ((ptr.flags & FLAG_P_LATCHABLE) != 0)
    {
      if (!ptr.hasFlagsAny(FLAG_P_CANT_LOCK))
        lockPointer(ptr, true);
      return false;
    }
    // Stop repeating: Latched key, no key
    if (ptr.hasFlagsAny(FLAG_P_LATCHED) || ptr.value == null)
      return false;
    KeyValue kv = KeyModifier.modify_long_press(ptr.value);
    if (!kv.equals(ptr.value))
    {
      ptr.value = kv;
      _handler.onPointerDown(kv, true);
      return true;
    }
    // Stop repeating: Special keys
    if (kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
      return false;
    _handler.onPointerHold(kv, ptr.modifiers);
    return true;
  }

  // Sliding

  void startSliding(Pointer ptr, float x)
  {
    stopKeyRepeat(ptr);
    ptr.flags |= FLAG_P_SLIDING;
    ptr.sliding = new Sliding(x);
  }

  /** Return the [FLAG_P_*] flags that correspond to pressing [kv]. */
  static int pointer_flags_of_kv(KeyValue kv)
  {
    int flags = 0;
    if (kv.hasFlagsAny(KeyValue.FLAG_LATCH))
    {
      // Non-special latchable key must clear modifiers and can't be locked
      if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
        flags |= FLAG_P_CLEAR_LATCHED | FLAG_P_CANT_LOCK;
      flags |= FLAG_P_LATCHABLE;
    }
    if (kv.hasFlagsAny(KeyValue.FLAG_LOCK))
      flags |= FLAG_P_LOCKABLE;
    return flags;
  }

  // Gestures

  /** Apply a gesture to the current key. */
  KeyValue apply_gesture(Pointer ptr, Gesture.Name gesture)
  {
    switch (gesture)
    {
      case None:
        return ptr.value;
      case Swipe:
        return ptr.value;
      case Roundtrip:
        return
          modify_key_with_extra_modifier(
              ptr,
              getNearestKeyAtDirection(ptr, ptr.gesture.current_direction()),
              KeyValue.Modifier.GESTURE);
      case Circle:
        return
          modify_key_with_extra_modifier(ptr, ptr.key.keys[0],
              KeyValue.Modifier.GESTURE);
      case Anticircle:
        return _handler.modifyKey(ptr.key.anticircle, ptr.modifiers);
    }
    return ptr.value; // Unreachable
  }

  KeyValue modify_key_with_extra_modifier(Pointer ptr, KeyValue kv,
      KeyValue.Modifier extra_mod)
  {
    return
      _handler.modifyKey(kv,
        ptr.modifiers.with_extra_mod(KeyValue.makeInternalModifier(extra_mod)));
  }

  // Pointers

  private static final class Pointer
  {
    /** -1 when latched. */
    public int pointerId;
    /** The Key pressed by this Pointer */
    public final KeyboardData.Key key;
    /** Gesture state, see [Gesture]. [null] means the pointer has not moved out of the center region. */
    public Gesture gesture;
    /** Selected value with [modifiers] applied. */
    public KeyValue value;
    public float downX;
    public float downY;
    /** Modifier flags at the time the key was pressed. */
    public Modifiers modifiers;
    /** See [FLAG_P_*] flags. */
    public int flags;
    /** Identify timeout messages. */
    public int timeoutWhat;
    /** [null] when not in sliding mode. */
    public Sliding sliding;

    public Pointer(int p, KeyboardData.Key k, KeyValue v, float x, float y, Modifiers m)
    {
      pointerId = p;
      key = k;
      gesture = null;
      value = v;
      downX = x;
      downY = y;
      modifiers = m;
      flags = (v == null) ? 0 : pointer_flags_of_kv(v);
      timeoutWhat = -1;
      sliding = null;
    }

    public boolean hasFlagsAny(int has)
    {
      return ((flags & has) != 0);
    }
  }

  public final class Sliding
  {
    /** Accumulated distance since last event. */
    float d = 0.f;
    /** The slider speed changes depending on the pointer speed. */
    float speed = 1.f;
    /** Coordinate of the last move. */
    float last_x;
    /** [System.currentTimeMillis()] at the time of the last move. */
    long last_move_ms;

    public Sliding(float x)
    {
      last_x = x;
      last_move_ms = System.currentTimeMillis();
    }

    static final float SPEED_SMOOTHING = 0.7f;
    /** Avoid absurdly large values. */
    static final float SPEED_MAX = 4.f;

    public void onTouchMove(Pointer ptr, float x)
    {
      d += (x - last_x) * speed / _config.slide_step_px;
      update_speed(x);
      // Send an event when [abs(d)] exceeds [1].
      int d_ = (int)d;
      if (d_ != 0)
      {
        d -= d_;
        int key_index = (d_ < 0) ? 5 : 6;
        ptr.value = _handler.modifyKey(ptr.key.keys[key_index], ptr.modifiers);
        send_key(ptr, Math.abs(d_));
      }
    }

    /** Handle a sliding pointer going up. Latched modifiers are not
        cleared to allow easy adjustments to the cursors. The pointer is
        cancelled. */
    public void onTouchUp(Pointer ptr)
    {
      removePtr(ptr);
      _handler.onPointerFlagsChanged(false);
    }

    /** Send the pressed key [n] times. */
    void send_key(Pointer ptr, int n)
    {
      if (ptr.value == null)
        return;
      // Avoid looping if possible to avoid lag while sliding fast
      KeyValue multiplied = multiply_key(ptr.value, n);
      if (multiplied != null)
        _handler.onPointerHold(multiplied, ptr.modifiers);
      else
        for (int i = 0; i < n; i++)
          _handler.onPointerHold(ptr.value, ptr.modifiers);
    }

    /** Return a key performing the same action as [kv] but [n] times. Returns
        [null] if [kv] cannot be multiplied. */
    KeyValue multiply_key(KeyValue kv, int n)
    {
      switch (kv.getKind())
      {
        case Cursor_move:
          return KeyValue.cursorMoveKey(kv.getCursorMove() * n);
      }
      return null;
    }

    /** [speed] is computed from the elapsed time and distance traveled
        between two move events. Exponential smoothing is used to smooth out
        the noise. Sets [last_move_ms] and [last_x]. */
    void update_speed(float x)
    {
      long now = System.currentTimeMillis();
      float instant_speed = Math.min(SPEED_MAX,
          Math.abs(x - last_x) / (float)(now - last_move_ms) + 1.f);
      speed = speed + (instant_speed - speed) * SPEED_SMOOTHING;
      last_move_ms = now;
      last_x = x;
    }
  }

  /** Represent modifiers currently activated.
      Sorted in the order they should be evaluated. */
  public static final class Modifiers
  {
    private final KeyValue[] _mods;
    private final int _size;

    private Modifiers(KeyValue[] m, int s)
    {
      _mods = m; _size = s;
    }

    public KeyValue get(int i) { return _mods[_size - 1 - i]; }
    public int size() { return _size; }
    public boolean has(KeyValue.Modifier m)
    {
      for (int i = 0; i < _size; i++)
      {
        KeyValue kv = _mods[i];
        switch (kv.getKind())
        {
          case Modifier:
            if (kv.getModifier().equals(m))
              return true;
        }
      }
      return false;
    }

    /** Return a copy of this object with an extra modifier added. */
    public Modifiers with_extra_mod(KeyValue m)
    {
      KeyValue[] newmods = Arrays.copyOf(_mods, _size + 1);
      newmods[_size] = m;
      return ofArray(newmods, newmods.length);
    }

    /** Returns the activated modifiers that are not in [m2]. */
    public Iterator<KeyValue> diff(Modifiers m2)
    {
      return new ModifiersDiffIterator(this, m2);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(_mods); }
    @Override
    public boolean equals(Object obj)
    {
      return Arrays.equals(_mods, ((Modifiers)obj)._mods);
    }

    public static final Modifiers EMPTY =
      new Modifiers(new KeyValue[0], 0);

    protected static Modifiers ofArray(KeyValue[] mods, int size)
    {
      // Sort and remove duplicates and nulls.
      if (size > 1)
      {
        Arrays.sort(mods, 0, size);
        int j = 0;
        for (int i = 0; i < size; i++)
        {
          KeyValue m = mods[i];
          if (m != null && (i + 1 >= size || m != mods[i + 1]))
          {
            mods[j] = m;
            j++;
          }
        }
        size = j;
      }
      return new Modifiers(mods, size);
    }

    /** Returns modifiers that are in [m1_] but not in [m2_]. */
    static final class ModifiersDiffIterator
        implements Iterator<KeyValue>
    {
      Modifiers m1;
      int i1 = 0;
      Modifiers m2;
      int i2 = 0;

      public ModifiersDiffIterator(Modifiers m1_, Modifiers m2_)
      {
        m1 = m1_;
        m2 = m2_;
        advance();
      }

      public boolean hasNext()
      {
        return i1 < m1._size;
      }

      public KeyValue next()
      {
        if (i1 >= m1._size)
          throw new NoSuchElementException();
        KeyValue m = m1._mods[i1];
        i1++;
        advance();
        return m;
      }

      /** Advance to the next element if [i1] is not a valid element. The end
          is reached when [i1 = m1.size()].  */
      void advance()
      {
        while (i1 < m1.size())
        {
          KeyValue m = m1._mods[i1];
          while (true)
          {
            if (i2 >= m2._size)
              return;
            int d = m.compareTo(m2._mods[i2]);
            if (d < 0)
              return;
            i2++;
            if (d == 0)
              break;
          }
          i1++;
        }
      }
    }
  }

  public interface IPointerEventHandler
  {
    /** Key can be modified or removed by returning [null]. */
    public KeyValue modifyKey(KeyValue k, Modifiers mods);

    /** A key is pressed. [getModifiers()] is uptodate. Might be called after a
        press or a swipe to a different value. Down events are not paired with
        up events. */
    public void onPointerDown(KeyValue k, boolean isSwipe);

    /** Key is released. [k] is the key that was returned by
        [modifySelectedKey] or [modifySelectedKey]. */
    public void onPointerUp(KeyValue k, Modifiers mods);

    /** Flags changed because latched or locked keys or cancelled pointers. */
    public void onPointerFlagsChanged(boolean shouldVibrate);

    /** Key is repeating. */
    public void onPointerHold(KeyValue k, Modifiers mods);
  }
}
