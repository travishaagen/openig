/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.openig.filter.throttling;

import static org.forgerock.json.JsonValueFunctions.duration;
import static org.forgerock.openig.heap.Keys.SCHEDULED_EXECUTOR_SERVICE_HEAP_KEY;
import static org.forgerock.openig.util.JsonValues.evaluated;
import static org.forgerock.openig.util.JsonValues.getWithDeprecation;
import static org.forgerock.openig.util.JsonValues.expression;
import static org.forgerock.openig.util.JsonValues.requiredHeapObject;

import java.util.concurrent.ScheduledExecutorService;

import org.forgerock.http.filter.throttling.FixedRateThrottlingPolicy;
import org.forgerock.http.filter.throttling.ThrottlingFilter;
import org.forgerock.http.filter.throttling.ThrottlingPolicy;
import org.forgerock.http.filter.throttling.ThrottlingRate;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.openig.el.Expression;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.heap.Keys;
import org.forgerock.util.Function;
import org.forgerock.util.time.Duration;
import org.forgerock.util.time.TimeService;

/**
 * Creates and initializes a throttling filter in a heap environment.
 *
 * Configuration options:
 *
 * <pre>
 * {@code {
 *      "type": "ThrottlingFilter",
 *      "config": {
 *         "executor"                     : executor            [OPTIONAL - by default uses 'ScheduledThreadPool'
 *                                                                          heap object]
 *         "cleaningInterval"             : duration            [OPTIONAL - The interval to wait for cleaning outdated
 *                                                                          buckets. Cannot be neither zero nor
 *                                                                          unlimited.
 *         "requestGroupingPolicy"        : expression<String>  [REQUIRED - Expression to evaluate whether a request
 *                                                                          matches when calculating a rate for a group
 *                                                                          of requests.]
 *         "rate": {
 *            "numberOfRequests"          : integer             [REQUIRED - The number of requests allowed to go through
 *                                                                          this filter during the duration window.]
 *            "duration"                  : duration            [REQUIRED - The time window during which we count the
 *                                                                          incoming requests.]
 *         }
 * OR
 *         "throttlingRatePolicy"         : reference or        [REQUIRED - the policy that will define the throttling
 *                                          inlined declaration             rate to apply]
 *      }
 *  }
 *  }
 * </pre>
 */
public class ThrottlingFilterHeaplet extends GenericHeaplet {

    static final Function<JsonValue, ThrottlingRate, JsonValueException> THROTTLING_RATE =
            new Function<JsonValue, ThrottlingRate, JsonValueException>() {

                @Override
                public ThrottlingRate apply(JsonValue value) {
                    int numberOfRequests = value.get("numberOfRequests").as(evaluated()).required().asInteger();
                    String duration = value.get("duration").as(evaluated()).required().asString();

                    return new ThrottlingRate(numberOfRequests, duration);
                }
            };

    static final Function<JsonValue, ThrottlingRate, JsonValueException> throttlingRate() {
        return THROTTLING_RATE;
    }

    private ThrottlingFilter filter;

    @Override
    public Object create() throws HeapException {
        TimeService time = heap.get(Keys.TIME_SERVICE_HEAP_KEY, TimeService.class);
        Duration cleaningInterval = config.get("cleaningInterval")
                                          .as(evaluated())
                                          .defaultTo("5 seconds")
                                          .as(duration());

        final Expression<String> requestGroupingPolicy =
                getWithDeprecation(config, logger, "requestGroupingPolicy", "partitionKey")
                        .defaultTo("").as(expression(String.class));

        ThrottlingPolicy throttlingRatePolicy;
        if (config.isDefined("rate")) {
            // Backward compatibility and ease of use : for fixed throttling rate we still allow to declare them easily
            // in the configuration
            throttlingRatePolicy = new FixedRateThrottlingPolicy(config.get("rate").as(throttlingRate()));
        } else {
            throttlingRatePolicy = config.get("throttlingRatePolicy")
                                         .required()
                                         .as(requiredHeapObject(heap, ThrottlingPolicy.class));
        }

        ScheduledExecutorService executorService = config.get("executor")
                                                         .defaultTo(SCHEDULED_EXECUTOR_SERVICE_HEAP_KEY)
                                                         .as(requiredHeapObject(heap, ScheduledExecutorService.class));

        return filter = new ThrottlingFilter(executorService,
                                             time,
                                             cleaningInterval,
                                             new ExpressionRequestAsyncFunction<>(requestGroupingPolicy),
                                             throttlingRatePolicy);
    }

    @Override
    public void destroy() {
        super.destroy();
        if (filter != null) {
            filter.stop();
        }
    }

}
