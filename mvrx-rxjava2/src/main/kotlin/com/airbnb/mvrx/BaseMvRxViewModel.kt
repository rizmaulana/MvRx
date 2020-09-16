package com.airbnb.mvrx

import android.util.Log
import androidx.annotation.RestrictTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import com.airbnb.mvrx.rxjava2.MvRxTuple1
import com.airbnb.mvrx.rxjava2.MvRxTuple2
import com.airbnb.mvrx.rxjava2.MvRxTuple3
import com.airbnb.mvrx.rxjava2.MvRxTuple4
import com.airbnb.mvrx.rxjava2.MvRxTuple5
import com.airbnb.mvrx.rxjava2.MvRxTuple6
import com.airbnb.mvrx.rxjava2.MvRxTuple7
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1

/**
 * Base ViewModel implementation that all other ViewModels should extend.
 */
abstract class BaseMvRxViewModel<S : MavericksState>(
    initialState: S
) : MavericksViewModel<S>(initialState) {
    private val tag by lazy { javaClass.simpleName }
    private val disposables = CompositeDisposable()
    private val lastDeliveredStates = ConcurrentHashMap<String, Any>()
    private val activeSubscriptions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Define a [LifecycleOwner] to control subscriptions between [BaseMvRxViewModel]s. This only
     * provides two states, [Lifecycle.State.RESUMED] and [Lifecycle.State.DESTROYED] as it follows
     * the [ViewModel] object lifecycle. That is, when instantiated the lifecycle will be
     * [Lifecycle.State.RESUMED] and when [ViewModel.onCleared] is called the lifecycle will be
     * [Lifecycle.State.DESTROYED].
     *
     * This is not publicly accessible as it should only be used to control subscriptions
     * between two view models.
     */
    private val lifecycleOwner: LifecycleOwner = LifecycleOwner { lifecycleRegistry }
    private val lifecycleRegistry = LifecycleRegistry(lifecycleOwner).apply { currentState = Lifecycle.State.RESUMED }

    override fun onCleared() {
        super.onCleared()
        disposables.dispose()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * Helper to map a [Single] to an [Async] property on the state object.
     */
    fun <T> Single<T>.execute(
        stateReducer: S.(Async<T>) -> S
    ) = toObservable().execute({ it }, null, stateReducer)

    /**
     * Helper to map a [Single] to an [Async] property on the state object.
     * @param mapper A map converting the Single type to the desired Async type.
     * @param stateReducer A reducer that is applied to the current state and should return the
     *                     new state. Because the state is the receiver and it likely a data
     *                     class, an implementation may look like: `{ copy(response = it) }`.
     */
    fun <T, V> Single<T>.execute(
        mapper: (T) -> V,
        stateReducer: S.(Async<V>) -> S
    ) = toObservable().execute(mapper, null, stateReducer)

    /**
     * Helper to map an [Observable] to an [Async] property on the state object.
     */
    fun <T> Observable<T>.execute(
        stateReducer: S.(Async<T>) -> S
    ) = execute({ it }, null, stateReducer)

    /**
     * Helper to map a [Completable] to an [Async] property on the state object.
     */
    fun Completable.execute(
        stateReducer: S.(Async<Unit>) -> S
    ) = toSingle { Unit }.execute(stateReducer)

    /**
     * Helper like [<T> Single<T>.execute] but with on demand IO thread Subscription
     */
    fun <T> Single<T>.executeOnIo(
        stateReducer: S.(Async<T>) -> S
    ) = toObservable()
        .execute({ it }, null, stateReducer)

    /**
     * Helper like [<T, V> Single<T>.execute] but with on demand IO thread Subscription
     */
    fun <T, V> Single<T>.executeOnIo(
        mapper: (T) -> V,
        stateReducer: S.(Async<V>) -> S
    ) = toObservable()
        .subscribeOn(Schedulers.io())
        .execute(mapper, null, stateReducer)

    /**
     * Helper like [<T> Observable<T>.execute] but with on demand IO thread Subscription
     */
    fun <T> Observable<T>.executeOnIo(
        stateReducer: S.(Async<T>) -> S
    ) = subscribeOn(Schedulers.io())
        .execute({ it }, null, stateReducer)

    /**
     * Helper like [Completable.execute] but with on demand IO thread Subscription
     */
    fun Completable.executeOnIo(
        stateReducer: S.(Async<Unit>) -> S
    ) = toSingle { Unit }.executeOnIo(stateReducer)

    /**
     * Execute an [Observable] and wrap its progression with [Async] property reduced to the global state.
     *
     * @param mapper A map converting the Observable type to the desired Async type.
     * @param successMetaData A map that provides metadata to set on the Success result.
     *                        It allows data about the original Observable to be kept and accessed later. For example,
     *                        your mapper could map a network request to just the data your UI needs, but your base layers could
     *                        keep metadata about the request, like timing, for logging.
     * @param stateReducer A reducer that is applied to the current state and should return the
     *                     new state. Because the state is the receiver and it likely a data
     *                     class, an implementation may look like: `{ copy(response = it) }`.
     *
     *  @see Success.metadata
     */
    fun <T, V> Observable<T>.execute(
        mapper: (T) -> V,
        successMetaData: ((T) -> Any)? = null,
        stateReducer: S.(Async<V>) -> S
    ): Disposable {
        val blockExecutions = config.onExecute(this@BaseMvRxViewModel)
        if (blockExecutions != MavericksViewModelConfig.BlockExecutions.No) {
            if (blockExecutions == MavericksViewModelConfig.BlockExecutions.WithLoading) {
                setState { stateReducer(Loading()) }
            }
            return Disposables.disposed()
        }

        // Intentionally didn't use RxJava's startWith operator. When withState is called right after execute then the loading reducer won't be enqueued yet if startWith is used.
        setState { stateReducer(Loading()) }

        return map<Async<V>> { value ->
            val success = Success(mapper(value))
            success.metadata = successMetaData?.invoke(value)
            success
        }
            .onErrorReturn { e ->
                Fail(e)
            }
            .subscribe { asyncData -> setState { stateReducer(asyncData) } }
            .disposeOnClear()
    }

    /**
     * Output all state changes to logcat.
     */
    fun logStateChanges() {
        if (!config.debugMode) return
        subscribe { Log.d(tag, "New State: $it") }
    }

    /**
     * For ViewModels that want to subscribe to itself.
     */
    protected fun subscribe(subscriber: (S) -> Unit): Disposable = onEachInternal(null, action = { subscriber(it) }).toDisposable()

    /**
     * Subscribe to state when this LifecycleOwner is started.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    fun subscribe(
        owner: LifecycleOwner,
        deliveryMode: DeliveryMode = RedeliverOnStart,
        subscriber: (S) -> Unit
    ): Disposable {
        return onEachInternal(owner, deliveryMode) { subscriber(it) }.toDisposable()
    }

    /**
     * For ViewModels that want to subscribe to another ViewModel.
     */
    protected fun <S : MavericksState> subscribe(
        viewModel: BaseMvRxViewModel<S>,
        subscriber: (S) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .onEach { subscriber(it) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for only a single property.
     */
    protected fun <A> selectSubscribe(
        prop1: KProperty1<S, A>,
        subscriber: (A) -> Unit
    ) = onEach1Internal(null, prop1, action = { subscriber(it) }).toDisposable()

    /**
     * Subscribe to state changes for only a single property in a different ViewModel.
     */
    protected fun <A, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        subscriber: (A) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple1(prop1.get(it)) }
            .distinctUntilChanged()
            .onEach { (a) -> subscriber(a) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    private fun <A> selectSubscribeInternal(
        owner: LifecycleOwner?,
        prop1: KProperty1<S, A>,
        deliveryMode: DeliveryMode,
        subscriber: (A) -> Unit
    ) = onEach1Internal(owner, prop1, deliveryMode) { subscriber(it) }.toDisposable()

    /**
     * Subscribe to changes in an async property. There are optional parameters for onSuccess
     * and onFail which automatically unwrap the value or error.
     */
    protected fun <T> asyncSubscribe(
        asyncProp: KProperty1<S, Async<T>>,
        onFail: ((Throwable) -> Unit)? = null,
        onSuccess: ((T) -> Unit)? = null
    ) = onAsyncInternal(null, asyncProp, onFail = { onFail?.invoke(it) }, onSuccess = { onSuccess?.invoke(it) }).toDisposable()

    /**
     * Subscribe to changes in an async property in a different ViewModel. There are optional parameters
     * for onSuccess and onFail which automatically unwrap the value or error.
     */
    protected fun <T, S : MavericksState> asyncSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        asyncProp: KProperty1<S, Async<T>>,
        onFail: ((Throwable) -> Unit)? = null,
        onSuccess: ((T) -> Unit)? = null
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple1(asyncProp.get(it)) }
            .distinctUntilChanged()
            .onEach { (asyncValue) ->
                if (onSuccess != null && asyncValue is Success) {
                    onSuccess(asyncValue())
                } else if (onFail != null && asyncValue is Fail) {
                    onFail(asyncValue.error)
                }
            }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for two properties.
     */
    protected fun <A, B> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        subscriber: (A, B) -> Unit
    ) = onEach2Internal(null, prop1, prop2, action = { a, b -> subscriber(a, b) }).toDisposable()

    /**
     * Subscribe to state changes for two properties in a different ViewModel.
     */
    protected fun <A, B, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        subscriber: (A, B) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple2(prop1.get(it), prop2.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b) -> subscriber(a, b) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for three properties.
     */
    protected fun <A, B, C> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        subscriber: (A, B, C) -> Unit
    ) = onEach3Internal(null, prop1, prop2, prop3, action = { a, b, c -> subscriber(a, b, c) }).toDisposable()

    /**
     * Subscribe to state changes for three properties in a different ViewModel.
     */
    protected fun <A, B, C, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        subscriber: (A, B, C) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple3(prop1.get(it), prop2.get(it), prop3.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b, c) -> subscriber(a, b, c) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for four properties.
     */
    protected fun <A, B, C, D> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        subscriber: (A, B, C, D) -> Unit
    ) = onEach4Internal(null, prop1, prop2, prop3, prop4, RedeliverOnStart) { a, b, c, d -> subscriber(a, b, c, d) }.toDisposable()

    /**
     * Subscribe to state changes for four properties in a different ViewModel.
     */
    protected fun <A, B, C, D, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        subscriber: (A, B, C, D) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple4(prop1.get(it), prop2.get(it), prop3.get(it), prop4.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b, c, d) -> subscriber(a, b, c, d) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for five properties.
     */
    protected fun <A, B, C, D, E> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        subscriber: (A, B, C, D, E) -> Unit
    ) = onEach5Internal(null, prop1, prop2, prop3, prop4, prop5, RedeliverOnStart) { a, b, c, d, e ->
        subscriber(a, b, c, d, e)
    }.toDisposable()

    /**
     * Subscribe to state changes for five properties in a different ViewModel.
     */
    protected fun <A, B, C, D, E, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        subscriber: (A, B, C, D, E) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple5(prop1.get(it), prop2.get(it), prop3.get(it), prop4.get(it), prop5.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b, c, d, e) -> subscriber(a, b, c, d, e) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for six properties.
     */
    protected fun <A, B, C, D, E, F> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        prop6: KProperty1<S, F>,
        subscriber: (A, B, C, D, E, F) -> Unit
    ) = onEach6Internal(null, prop1, prop2, prop3, prop4, prop5, prop6, RedeliverOnStart) { a, b, c, d, e, f ->
        subscriber(a, b, c, d, e, f)
    }.toDisposable()

    /**
     * Subscribe to state changes for six properties in a different ViewModel.
     */
    protected fun <A, B, C, D, E, F, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        prop6: KProperty1<S, F>,
        subscriber: (A, B, C, D, E, F) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple6(prop1.get(it), prop2.get(it), prop3.get(it), prop4.get(it), prop5.get(it), prop6.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b, c, d, e, f) -> subscriber(a, b, c, d, e, f) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    /**
     * Subscribe to state changes for seven properties.
     */
    protected fun <A, B, C, D, E, F, G> selectSubscribe(
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        prop6: KProperty1<S, F>,
        prop7: KProperty1<S, G>,
        subscriber: (A, B, C, D, E, F, G) -> Unit
    ) = onEach7Internal(null, prop1, prop2, prop3, prop4, prop5, prop6, prop7, RedeliverOnStart) { a, b, c, d, e, f, g ->
        subscriber(a, b, c, d, e, f, g)
    }.toDisposable()

    /**
     * Subscribe to state changes for seven properties in a different ViewModel.
     */
    protected fun <A, B, C, D, E, F, G, S : MavericksState> selectSubscribe(
        viewModel: BaseMvRxViewModel<S>,
        prop1: KProperty1<S, A>,
        prop2: KProperty1<S, B>,
        prop3: KProperty1<S, C>,
        prop4: KProperty1<S, D>,
        prop5: KProperty1<S, E>,
        prop6: KProperty1<S, F>,
        prop7: KProperty1<S, G>,
        subscriber: (A, B, C, D, E, F, G) -> Unit
    ) {
        assertSubscribeToDifferentViewModel(viewModel)
        viewModel.stateFlow
            .map { MvRxTuple7(prop1.get(it), prop2.get(it), prop3.get(it), prop4.get(it), prop5.get(it), prop6.get(it), prop7.get(it)) }
            .distinctUntilChanged()
            .onEach { (a, b, c, d, e, f, g) -> subscriber(a, b, c, d, e, f, g) }
            .launchIn(viewModelScope)
            .cancelOnClear(viewModel.viewModelScope)
    }

    private fun Job.cancelOnClear(scope: CoroutineScope): Job {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            cancel()
        }
        return this
    }

    protected fun Disposable.disposeOnClear(): Disposable {
        disposables.add(this)
        return this
    }

    private fun Job.toDisposable() = Disposables.fromAction {
        cancel()
    }

    private fun <S : MavericksState> assertSubscribeToDifferentViewModel(viewModel: BaseMvRxViewModel<S>) {
        require(this != viewModel) {
            "This method is for subscribing to other view models. Please pass a different instance as the argument."
        }
    }
}
