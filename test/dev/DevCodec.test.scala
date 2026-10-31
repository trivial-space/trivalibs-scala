package trivalibs.dev

import munit.FunSuite
import trivalibs.graphics.math.cpu.Quat
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.utils.js.*

import scala.scalajs.js

// Pure round-trip tests for the JSON codecs. The storage / import.meta paths
// need a real browser and aren't exercised here; the codec logic is pure.
class DevCodecTest extends FunSuite:

  // Round-trip through actual JSON text, mirroring what sessionStorage stores.
  def roundTrip[T: DevCodec](value: T): Opt[T] =
    val codec = summon[DevCodec[T]]
    val text = js.JSON.stringify(codec.encode(value))
    codec.decode(js.JSON.parse(text))

  test("Double round-trips"):
    assertEquals(roundTrip(3.5).getOr(0.0), 3.5)

  test("Int round-trips"):
    assertEquals(roundTrip(42).getOr(0), 42)

  test("Boolean round-trips"):
    assertEquals(roundTrip(true).getOr(false), true)

  test("String round-trips"):
    assertEquals(roundTrip("hello").getOr(""), "hello")

  test("Float round-trips"):
    assertEquals(roundTrip(1.5f).getOr(0f), 1.5f)

  test("Vec3 round-trips"):
    val v = roundTrip(Vec3(1, 2, 3)).getOr(Vec3.zero)
    assertEqualsDouble(v.x, 1.0, 1e-9)
    assertEqualsDouble(v.y, 2.0, 1e-9)
    assertEqualsDouble(v.z, 3.0, 1e-9)

  test("Quat round-trips"):
    val q = roundTrip(Quat(0.1, 0.2, 0.3, 0.9)).getOr(Quat.identity)
    assertEqualsDouble(q.x, 0.1, 1e-9)
    assertEqualsDouble(q.y, 0.2, 1e-9)
    assertEqualsDouble(q.z, 0.3, 1e-9)
    assertEqualsDouble(q.w, 0.9, 1e-9)

  // --- malformed / mismatched input falls back to null (→ caller's init) ---

  test("decode of wrong type returns null"):
    val codec = summon[DevCodec[Double]]
    assert(codec.decode(js.JSON.parse("\"not a number\"")) == null)

  test("decode of too-short array returns null"):
    val codec = summon[DevCodec[Vec3]]
    assert(codec.decode(js.JSON.parse("[1, 2]")) == null)

  test("decode of non-array for Vec3 returns null"):
    val codec = summon[DevCodec[Vec3]]
    assert(codec.decode(js.JSON.parse("42")) == null)
