package trivalibs.utils.js

import munit.FunSuite

import scala.scalajs.js

/** The `Opt` / `Maybe` operations exist only on values typed optional, so they
  * never shadow `js.Dynamic` or `Selectable` members of the same name. See
  * `documents/done/opt-maybe-extension-shadowing-plan.md`.
  */
class OptMaybeShadowingTest extends FunSuite:

  final class Box(val n: Int)

  class Accessor extends Selectable:
    type Fields = (get: String, getOr: String, orElse: String)
    def selectDynamic(name: String): Any = s"field:$name"

  private def jsMap(): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Map)()

  test("js.Dynamic: get / set reach the JS Map methods"):
    val m = jsMap()
    m.set("k", 42)
    assertEquals(m.get("k").asInstanceOf[Int], 42)

  test("Selectable: fields named like the optional ops win"):
    val acc = Accessor()
    assertEquals(acc.get, "field:get")
    assertEquals(acc.getOr, "field:getOr")
    assertEquals(acc.orElse, "field:orElse")

  test("Opt[js.Dynamic]: get is the Opt op, then Map.get"):
    val m = jsMap()
    m.set("k", 7)
    val od: Opt[js.Dynamic] = m
    assertEquals(od.get.get("k").asInstanceOf[Int], 7)
    val none: Opt[js.Dynamic] = null
    assertEquals(none.getOr(m).get("k").asInstanceOf[Int], 7)

  test("Opt: get / getOr / orElse on strings, classes and Ints"):
    val s: Opt[String] = "a"
    val sn: Opt[String] = null
    val b: Opt[Box] = null
    val i: Opt[Int] = 3
    assertEquals(s.get.length, 1)
    assertEquals(sn.getOr("d"), "d")
    assertEquals(sn.orElse("alt").get, "alt")
    assertEquals(b.getOr(Box(5)).n, 5)
    assertEquals(i.getOr(0) + 1, 4)

  test("Maybe: get / getOr / orElse"):
    val m: Maybe[Int] = 3
    val mu: Maybe[Int] = Maybe.Not
    assertEquals(m.get + 1, 4)
    assertEquals(mu.getOr(7), 7)
    assertEquals(mu.orElse(9).get, 9)

  test("Maybe: get on undefined is a cast, not UndefOrOps.get (which throws)"):
    val mu: Maybe[js.Any] = Maybe.Not
    assert(js.isUndefined(mu.get))

  test("generic Opt[T] / Maybe[T] resolve"):
    def opt[T](x: Opt[T], d: T): T = x.getOr(d)
    def may[T](x: Maybe[T], d: T): T = x.getOr(d)
    assertEquals(opt[String](null, "g"), "g")
    assertEquals(may[String](Maybe.Not, "m"), "m")

  test("an expected js.Any result type doesn't break inference"):
    val s: Opt[String] = "fmt"
    val lit = Obj.literal(a = s.get, b = s.getOr("x"))
    assertEquals(lit.a.asInstanceOf[String], "fmt")

  test("Maybe[Opt[A]]: ops act on the undefined layer first"):
    def f(c: Maybe[Opt[Box]] = Maybe.Not): Int =
      c.getOr(Box(1)).getOr(Box(0)).n
    assertEquals(f(), 1)
    assertEquals(f(null), 0)
    assertEquals(f(Box(7)), 7)
    val notPassed: Maybe[Opt[Box]] = Maybe.Not
    assert(notPassed.orElse(null) == null)

  test("ops don't compile on non-optional values"):
    assert(compileErrors("\"x\".get").nonEmpty)
    assert(compileErrors("\"x\".getOr(\"y\")").nonEmpty)
    assert(compileErrors("Box(1).orElse(Box(2))").nonEmpty)
