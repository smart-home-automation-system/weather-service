package cloud.cholewa.weather.history;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

//The queries of the history one after the other: the pool has two connections, and two ranges at once
//would leave none for the row of the minute or the watch over the station. Each query waits for the
//end of the one before it - completed, failed or cancelled - without blocking a thread: the turn is a
//Mono that completes when the work before it ends. A query cancelled while it waits hands its turn on
//only once the one before it has ended too - never two queries at once
final class OneAtATime {

    private final AtomicReference<Mono<Void>> last = new AtomicReference<>(Mono.empty());

    <T> Mono<T> run(final Mono<T> work) {
        return Mono.defer(() -> {
            final Sinks.Empty<Void> done = Sinks.empty();
            final Mono<Void> before = last.getAndSet(done.asMono());
            return before
                .then(work)
                .doFinally(signal -> before.subscribe(null, null, done::tryEmitEmpty));
        });
    }
}
