package cloud.cholewa.weather.history;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OneAtATimeTest {

    private final OneAtATime sut = new OneAtATime();

    @Test
    void should_start_a_query_only_after_the_one_before_it_ended() {
        final Sinks.One<String> first = Sinks.one();
        final AtomicBoolean secondStarted = new AtomicBoolean();

        final StepVerifier firstQuery = sut.run(first.asMono()).as(StepVerifier::create).expectNext("first").expectComplete().verifyLater();
        final StepVerifier secondQuery = sut.run(Mono.fromCallable(() -> {
            secondStarted.set(true);
            return "second";
        })).as(StepVerifier::create).expectNext("second").expectComplete().verifyLater();

        assertThat(secondStarted).isFalse();
        first.tryEmitValue("first");

        firstQuery.verify();
        secondQuery.verify();
        assertThat(secondStarted).isTrue();
    }

    //a failed query must not hold the next one, nor fail it
    @Test
    void should_go_on_after_a_failed_query() {
        sut.run(Mono.error(new IllegalStateException("database away"))).as(StepVerifier::create)
            .verifyError(IllegalStateException.class);

        sut.run(Mono.just("next")).as(StepVerifier::create).expectNext("next").verifyComplete();
    }

    //a caller that went away (the dashboard closed) lets the next one in
    @Test
    void should_go_on_after_a_cancelled_query() {
        sut.run(Mono.never()).as(StepVerifier::create).thenCancel().verify();

        sut.run(Mono.just("next")).as(StepVerifier::create).expectNext("next").verifyComplete();
    }
}
