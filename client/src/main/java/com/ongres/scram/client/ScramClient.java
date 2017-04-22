/*
 * Copyright 2017, OnGres.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */


package com.ongres.scram.client;


import com.ongres.scram.common.ScramMechanisms;
import com.ongres.scram.common.gssapi.Gs2CbindFlag;
import com.ongres.scram.common.message.ClientFirstMessage;
import com.ongres.scram.common.stringprep.StringPreparation;
import com.ongres.scram.common.util.CryptoUtil;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.ongres.scram.common.util.Preconditions.*;


/**
 * A class that represents a SCRAM client. Use this class to perform a SCRAM negotiation with a SCRAM server.
 * This class supports channel binding and the string preparation mechanisms provided by module scram-common.
 *
 * The class is fully configurable, including options to selected the desired channel binding,
 * automatically pick the best client SCRAM mechanism based on those supported (advertised) by the server,
 * selecting an externally-provided SecureRandom instance or an external nonceProvider, or choosing the nonce length.
 *
 * This class is thread-safe if the two following conditions are met:
 * <ul>
 *     <li>The SecureRandom used ({@link SecureRandom} by default) are thread-safe too.
 *         The contract of {@link java.util.Random} marks it as thread-safe, so inherited classes are also expected
 *         to maintain it.
 *     </li>
 *     <li>No external nonceSupplier is provided; or if provided, it is thread-safe.</li>
 * </ul>
 * So this class, once instantiated via the {@link Builder#get()}} method, can serve for multiple users and
 * authentications. However, once the authentication starts and a
 * {@link com.ongres.scram.common.message.ClientFirstMessage} is generated, it is meant to be used by a single
 * user/authentication process.
 */
public class ScramClient {
    /**
     * Length (in characters, bytes) of the nonce generated by default (if no nonce supplier is provided)
     */
    public static final int DEFAULT_NONCE_LENGTH = 24;

    /**
     * Select whether this client will support channel binding or not
     */
    public enum ChannelBinding {
        /**
         * Don't use channel binding. Server must support at least one non-channel binding mechanism.
         */
        NO(Gs2CbindFlag.CLIENT_NOT),

        /**
         * Force use of channel binding. Server must support at least one channel binding mechanism.
         * Channel binding data will need to be provided as part of the ClientFirstMessage.
         */
        YES(Gs2CbindFlag.CHANNEL_BINDING_REQUIRED),

        /**
         * Channel binding is preferred. Non-channel binding mechanisms will be used if either the server does not
         * support channel binding, or no channel binding data is provided as part of the ClientFirstMessage
         */
        IF_SERVER_SUPPORTS_IT(Gs2CbindFlag.CLIENT_YES_SERVER_NOT)
        ;

        private final Gs2CbindFlag gs2CbindFlag;

        ChannelBinding(Gs2CbindFlag gs2CbindFlag) {
            this.gs2CbindFlag = gs2CbindFlag;
        }

        public Gs2CbindFlag gs2CbindFlag() {
            return gs2CbindFlag;
        }
    }

    private final ChannelBinding channelBinding;
    private final StringPreparation stringPreparation;
    private final Optional<ScramMechanisms> nonChannelBindingMechanism;
    private final Optional<ScramMechanisms> channelBindingMechanism;
    private final SecureRandom secureRandom;
    private final Supplier<String> nonceSupplier;

    private ScramClient(
            ChannelBinding channelBinding, StringPreparation stringPreparation,
            Optional<ScramMechanisms> nonChannelBindingMechanism, Optional<ScramMechanisms> channelBindingMechanism,
            SecureRandom secureRandom, Supplier<String> nonceSupplier
    ) {
        if(! (nonChannelBindingMechanism.isPresent() || channelBindingMechanism.isPresent())) {
            throw new IllegalArgumentException("Either a channel-binding or a non-binding mechanism must be present");
        }

        this.channelBinding = checkNotNull(channelBinding, "channelBinding");
        this.stringPreparation = checkNotNull(stringPreparation, "stringPreparation");
        this.nonChannelBindingMechanism = nonChannelBindingMechanism;
        this.channelBindingMechanism = channelBindingMechanism;
        this.secureRandom = checkNotNull(secureRandom, "secureRandom");
        this.nonceSupplier = checkNotNull(nonceSupplier, "nonceSupplier");
    }

    /**
     * Selects for the client whether to use channel binding.
     * Refer to {@link ChannelBinding} documentation for the description of the possible values.
     * @param channelBinding The channel binding setting
     * @throws IllegalArgumentException If channelBinding is null
     */
    public static PreBuilder1 channelBinding(ChannelBinding channelBinding) throws IllegalArgumentException {
        return new PreBuilder1(checkNotNull(channelBinding, "channelBinding"));
    }

    public static class PreBuilder1 {
        protected final ChannelBinding channelBinding;

        private PreBuilder1(ChannelBinding channelBinding) {
            this.channelBinding = channelBinding;
        }

        /**
         * Selects the string preparation algorithm to use by the client.
         * @param stringPreparation The string preparation algorithm
         * @throws IllegalArgumentException If stringPreparation is null
         */
        public PreBuilder2 stringPreparation(StringPreparation stringPreparation) throws IllegalArgumentException {
            return new PreBuilder2(channelBinding, checkNotNull(stringPreparation, "stringPreparation"));
        }
    }

    public static class PreBuilder2 extends PreBuilder1 {
        protected final StringPreparation stringPreparation;
        protected Optional<ScramMechanisms> nonChannelBindingMechanism = Optional.empty();
        protected Optional<ScramMechanisms> channelBindingMechanism = Optional.empty();

        private PreBuilder2(ChannelBinding channelBinding, StringPreparation stringPreparation) {
            super(channelBinding);
            this.stringPreparation = stringPreparation;
        }

        /**
         * Inform the client of the SCRAM mechanisms supported by the server.
         * Based on this list, the channel binding settings previously specified,
         * and the relative strength of the supported SCRAM mechanisms for this client,
         * the client will have enough data to select which mechanism to use for future interactions with the server.
         * All names provided here need to be standar IANA Registry names for SCRAM mechanisms, or will be ignored.
         *
         * @see <a href="https://www.iana.org/assignments/sasl-mechanisms/sasl-mechanisms.xhtml#scram">
         *      SASL SCRAM Family Mechanisms</a>
         *
         * @param serverMechanisms One or more IANA-registered SCRAM mechanism names, as advertised by the server
         * @throws IllegalArgumentException If no server mechanisms are provided
         */
        public Builder serverMechanisms(String... serverMechanisms) {
            checkArgument(null != serverMechanisms && serverMechanisms.length > 0, "serverMechanisms");

            nonChannelBindingMechanism = ScramMechanisms.selectMatchingMechanism(false, serverMechanisms);
            if(channelBinding == ChannelBinding.NO && ! nonChannelBindingMechanism.isPresent()) {
                throw new IllegalArgumentException("Server does not support non channel binding mechanisms");
            }

            channelBindingMechanism = ScramMechanisms.selectMatchingMechanism(true, serverMechanisms);
            if(channelBinding == ChannelBinding.YES && ! channelBindingMechanism.isPresent()) {
                throw new IllegalArgumentException("Server does not support channel binding mechanisms");
            }

            if(! (channelBindingMechanism.isPresent() || nonChannelBindingMechanism.isPresent())) {
                throw new IllegalArgumentException("There are no matching mechanisms between client and server");
            }

            return new Builder(channelBinding, stringPreparation, nonChannelBindingMechanism, channelBindingMechanism);
        }

        /**
         * Inform the client of the SCRAM mechanisms supported by the server.
         * Calls {@link Builder#serverMechanisms(String...)} with the results of splitting the received comma-separated
         * values.
         * @param serverMechanismsCsv A CSV (Comma-Separated Values) String, containining all the SCRAM mechanisms
         *                            supported by the server
         * @throws IllegalArgumentException If serverMechanismsCsv is null
         */
        public Builder serverMechanismsCsv(String serverMechanismsCsv) throws IllegalArgumentException {
            return serverMechanisms(checkNotNull(serverMechanismsCsv, "serverMechanismsCsv").split(","));
        }
    }

    public static class Builder extends PreBuilder2 {
        private final Optional<ScramMechanisms> nonChannelBindingMechanism;
        private final Optional<ScramMechanisms> channelBindingMechanism;

        private SecureRandom secureRandom = new SecureRandom();
        private Supplier<String> nonceSupplier;
        private int nonceLength = DEFAULT_NONCE_LENGTH;

        private Builder(
                ChannelBinding channelBinding, StringPreparation stringPreparation,
                Optional<ScramMechanisms> nonChannelBindingMechanism, Optional<ScramMechanisms> channelBindingMechanism
        ) {
            super(channelBinding, stringPreparation);
            this.nonChannelBindingMechanism = nonChannelBindingMechanism;
            this.channelBindingMechanism = channelBindingMechanism;
        }

        /**
         * Optional call. Selects a non-default SecureRandom instance,
         * based on the given algorithm and optionally provider.
         * This SecureRandom instance will be used to generate secure random values,
         * like the ones required to generate the nonce
         * (unless an external nonce provider is given via {@link Builder#nonceSupplier(Supplier<String)}).
         * Algorithm and provider names are those supported by the {@link SecureRandom} class.         *
         * @param algorithm The name of the algorithm to use.
         * @param provider The name of the provider of SecureRandom. Might be null.
         * @throws IllegalArgumentException If algorithm is null, or either the algorithm or provider are not supported
         */
        public Builder secureRandomAlgorithmProvider(String algorithm, String provider)
        throws IllegalArgumentException {
            checkNotNull(algorithm, "algorithm");
            try {
                secureRandom = null == provider ?
                        SecureRandom.getInstance(algorithm) :
                        SecureRandom.getInstance(algorithm, provider);
            } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
                throw new IllegalArgumentException("Invalid algorithm or provider", e);
            }

            return this;
        }

        /**
         * Optional call. The client will use a default nonce generator,
         * unless an external one is provided by this method.         *
         * @param nonceSupplier A supplier of valid nonce Strings.
         *                      Please note that according to the
         *                      <a href="https://tools.ietf.org/html/rfc5802#section-7">SCRAM RFC</a>
         *                      only ASCII printable characters (except the comma, ',') are permitted on a nonce.
         *                      Length is not limited.
         * @throws IllegalArgumentException If nonceSupplier is null
         */
        public Builder nonceSupplier(Supplier<String> nonceSupplier) throws IllegalArgumentException {
            this.nonceSupplier = checkNotNull(nonceSupplier, "nonceSupplier");

            return this;
        }

        /**
         * Sets a non-default ({@link ScramClient#DEFAULT_NONCE_LENGTH}) length for the nonce generation,
         * if no alternate nonceSupplier is provided via {@link Builder#nonceSupplier(Supplier<String>)}.
         * @param length The length of the nonce. Must be positive and greater than 0
         * @throws IllegalArgumentException If length is less than 1
         */
        public Builder nonceLength(int length) throws IllegalArgumentException {
            this.nonceLength = gt0(length, "length");

            return this;
        }

        /**
         * Gets the client, fully constructed and configured, with the provided channel binding, string preparation
         * properties, and the selected SCRAM mechanism based on server supported mechanisms.
         * If no SecureRandom algorithm and provider were provided, a default one would be used.
         * If no nonceSupplier was provided, a default nonce generator would be used,
         * of the {@link ScramClient#DEFAULT_NONCE_LENGTH} length, unless {@link Builder#nonceLength(int)} is called.
         * @return The fully built instance.
         */
        public ScramClient get() {
            return new ScramClient(
                    channelBinding, stringPreparation, nonChannelBindingMechanism, channelBindingMechanism,
                    secureRandom,
                    nonceSupplier != null ? nonceSupplier : () -> CryptoUtil.nonce(nonceLength, secureRandom)
            );
        }
    }

    /**
     * List all the supported SCRAM mechanisms by this client implementation
     * @return A list of the IANA-registered, SCRAM supported mechanisms
     */
    public static List<String> supportedMechanisms() {
        return Arrays.stream(ScramMechanisms.values()).map(m -> m.getName()).collect(Collectors.toList());
    }

    /**
     * Returns a {@link ClientFirstMessage.Builder} to later parametrize a {@link ClientFirstMessage}.
     * @param user The user for the client-first-message
     * @return The Builder
     * @throws IllegalArgumentException If user is null or empty
     */
    public ClientFirstMessage.Builder clientFirstMessageBuilder(String user) throws IllegalArgumentException {
        checkNotEmpty(user, "user");
        return new ClientFirstMessage.Builder(channelBinding.gs2CbindFlag(), user, nonceSupplier.get());
    }

    /**
     * Returns a {@link ClientFirstMessage} with the specified user, without channel binding or alternative authzid.
     * @param user The user for the client-first-message
     * @return The client-first-message instance
     * @throws IllegalArgumentException If user is null or empty
     */
    public ClientFirstMessage clientFirstMessage(String user) throws IllegalArgumentException {
        return clientFirstMessageBuilder(user).get();
    }
}
