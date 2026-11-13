package app.hush.music.innertube

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One wire budget is inherited by nested retries and quality fallbacks. */
class StreamRequestBudget(val limit: Int = 18) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StreamRequestBudget>
    private val requests = AtomicInteger()
    fun acquire(): Int {
        val n = requests.incrementAndGet()
        if (n > limit) throw StreamRequestBudgetExceeded()
        return n
    }
    fun used(): Int = requests.get().coerceAtMost(limit)
}
class StreamRequestBudgetExceeded : IOException("음원 주소 조회 요청 한도 초과")
