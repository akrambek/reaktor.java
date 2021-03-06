/**
 * Copyright 2016-2020 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.reaktor.test.internal.k3po.ext.behavior;

import java.util.Objects;

public enum NukleusThrottleMode
{
    STREAM,
    MESSAGE,
    NONE;

    public static NukleusThrottleMode decode(
        String value)
    {
        Objects.requireNonNull(value);

        // backwards compatibility
        switch (value)
        {
        case "on":
            value = "stream";
            break;
        case "off":
            value = "none";
            break;
        default:
            break;
        }

        switch (value)
        {
        case "stream":
            return STREAM;
        case "message":
            return MESSAGE;
        case "none":
            return NONE;
        default:
            throw new IllegalArgumentException(value);
        }
    }
}
