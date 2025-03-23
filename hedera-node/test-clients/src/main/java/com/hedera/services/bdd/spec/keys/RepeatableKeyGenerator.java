// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.services.bdd.spec.keys.DefaultKeyGen.protoEcdsaKeyWith;
import static com.hedera.services.bdd.spec.keys.DefaultKeyGen.protoEd25519KeyWith;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.ecdsaFrom;
import static org.hiero.consensus.model.utility.CommonUtils.hex;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.hedera.services.bdd.spec.keys.deterministic.Ed25519Factory;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.utility.CommonUtils;

/**
 * A {@link KeyGenerator} that generates repeatable keys for testing purposes.
 */
public class RepeatableKeyGenerator implements KeyGenerator {
    private record RepeatableKey(String hexedPublicKey, PrivateKey privateKey, Key protoKey) {}

    private final List<RepeatableKey> ecdsaKeys;
    private final List<RepeatableKey> ed25519Keys;

    private int ed25519Index = 0;
    private int ecdsaIndex = 0;

    public RepeatableKeyGenerator() {
        ed25519Keys = Arrays.stream(TEST_ONLY_ED25519_KEYS)
                .map(CommonUtils::unhex)
                .map(Ed25519Factory::ed25519From)
                .map(pk -> new RepeatableKey(hex(pk.getAbyte()), pk, protoEd25519KeyWith(pk.getAbyte())))
                .toList();
        ecdsaKeys = Arrays.stream(TEST_ONLY_ECDSA_KEYS)
                .map(k -> k.split("[|]"))
                .map(parts -> new RepeatableKey(
                        parts[0], ecdsaFrom(new BigInteger(parts[1], 16)), protoEcdsaKeyWith(unhex(parts[0]))))
                .toList();
    }

    @Override
    public Key genEd25519AndUpdateMap(@NonNull final Map<String, PrivateKey> mutablePkMap) {
        final var repeatable = ed25519Keys.get(ed25519Index++);
        mutablePkMap.put(repeatable.hexedPublicKey(), repeatable.privateKey());
        ed25519Index %= ed25519Keys.size();
        return repeatable.protoKey();
    }

    @Override
    public Key genEcdsaSecp256k1AndUpdate(@NonNull final Map<String, PrivateKey> mutablePkMap) {
        final var repeatable = ecdsaKeys.get(ecdsaIndex++);
        mutablePkMap.put(repeatable.hexedPublicKey(), repeatable.privateKey());
        ecdsaIndex %= ecdsaKeys.size();
        return repeatable.protoKey();
    }

    private static final String[] TEST_ONLY_ED25519_KEYS = {
        "5e5aa9d8fcea6a491aed406a95244de14a0ede46e602aabc39802b6a888f826f",
        "96bbbabf73c5fb5e6d3d8f745dd9b0c642e59ce6b1a14c017d0a90c61895ddeb",
        "e132afff12653417deda1e359a6fe99ea6542593b7f49ad851d4cb129282e2ab",
        "152b0c6127bb03a1935e0eef10cb37e1294f3e10e7a2f9581cf238368f581d45",
        "a4c1ce33d4d3ef5c4b8305b7b2832de145a84910c8d9c24fc73a409d04464fcb",
        "b25bdb72394588d1e04062b5e0f4f0862d0a50990a2187d0536f90d7862825c9",
        "40cecac35f68c431f4160a6b13b5deb8256fdccf93d5f1898a8b8cc0f7bb6b78",
        "ac6d757891976d401139b7b08256d89dde61aecd67c64156d51ea0b42affffdc",
        "5a81f3d0afe5946d21fad8de0e164fb298132590825deb7a43922852373a743f",
        "2d0239df440b1068a77bcc584cbafc7fd1c1c7767ea378039badbfa65a02df95",
        "1705080608a201eef06b92e2e0049f16b720a731995341bf1bba46ef27639f64",
        "3a12fb2f194d7d5fc923a58866b1360266503783f6c13db4dfe130fcabceaaf9",
        "ae6104f89c64d67d434f9b6176b01a5a16715beeae506d6b285d4ee8595bb51d",
        "9ee777af386aa434392fabdd2551be6785d3d62527f9afe0a4b47828a16c8356",
        "445c1a2309d4225750c739235c752fd6fcc1f663551716e0b92df0a3a5a22c1f",
        "c366433dc43d4dec03316864dc45e7670dc71841ce2ad6eb22d39402a5a3b7da",
        "dcc03850adc8cc67ffb54ae17910227c4ece497b8e767d4aea3d456fbe0d276b",
        "017368e5c7a4a904c8753edef564d7e1e5686624cd8e9ccdd0cd4b2ebe32b5ac",
        "9044bf79db5f5626e88c79edb9915f316cbeff1b603c5a091d22635763b5401b",
        "6df0cfb087df54d1affa7597387185be3c566e6d49cddd21cb5e46163375f975",
        "6681d7587a5561b5feadead9c1f7f184a2ba76e3aa899ad035add46d4582502b",
        "dce6dd521df5bc454c65e98a9c329001318adbea9e00af4f5a4de8094e6c226b",
        "8a99b134f04d133e33ff15b74d041cbfe80f2aca4cb8c7ac97598980e28f65e2",
        "c1a0920bffc30881d8b6ba97ba854d931b7bceff4030b5c2d64c229657bd7a4a",
        "5e9d56bbe2f54ceea3c19a53274c9c224b65c7aa10bd03ba6b5643461e785ab5",
        "cdf9a7a57df26d329df37f596c44c1643cfdf45302d1ae87179bd285861aac46",
        "00a82645da16bdecc167efb1dce760f25bfb1e0d9f354b9ea983ff7706985791",
        "79d85e3bbd9e75da75a55395ac2a809c7d2a8d0522999b1e908bcbaa579b426d",
        "9a753446ebd840a0b12f46315b7aebcd238c186c4f69ac6bd7eb75fe8ea601bb",
        "f82dc89960db0c69bde1685127f07311a7f521cf437f3f6f44112952f6c7d260",
        "a1173ed4a3f32a837178eb94592a6e93b3ca669093a63eac5563b81c86393afd",
        "e10f4932bda53b6b43e9a8cb7b6356ade6fcdb1562c34a544c3ae58c4212e89a",
        "74107c98671660c5c533e394043d71ec475855129a6ffec836575c53af46e090",
        "0192672f3bf4b3f7ebbb54fe770172dcb51172080d3226a3c58adcbd1f6360e7",
        "dd6c42c88b54cd1f0bf08cb3580ae3317db7d29b4217826b61bdcb208ad17ffd",
        "c2f19feed2a69a78f3c41d382b73e8f9119d4f6b308ded333580541fac1c9c65",
        "6ed730ad29b481843393e3561b193c361bdf66986108f38783dc8ae542cc0838",
        "906a1b32e8c045827f0cfa8e21aad6df4e920a3168e908264f00e2449d6f358f",
        "6ccac600e4ff52bb99203c212570baafbab3eac9d30be88ee75c5a24cb1e9f6e",
        "9af457250753f307ae86b98d59698978ef4ce7b441f1144bf58eab249cf06589",
        "55bf74b2312623c379257bd1b773cc9ab6d66138352073fa014ab4eb546adb04",
        "fd93d3a9c40e0131d48347fe1141020210ec4e4edc297a1a836de44cd15476d6",
    };

    private static final String[] TEST_ONLY_ECDSA_KEYS = {
        "0230e6b4adbf4a4ec5d4ef712f223e224a0bc572cd060827f77560f8042c837837|b62d0c1203a3a2394f7a7d392fecf4e1edecabb48c3165324433565f6e21dae3",
        "03be08dedd0e0111ed3d12ad6e6ed237cb2f8548150b3d310833fd76470a1f357f|e4e4b74e1e44b50ca750088af0e6838f2869c452120aa4c2d0e1b6c65088dc7c",
        "03832a0fcedf9c95d16b61f48795aaa0e6b8d79404277e5f7c2f3f6d68edcccb6a|31de5c4169d7a7c07c62f984134db80d4a455318f95b9b12dd8bd42c10424d87",
        "0386926561a753dcd31baabd9b61e41af166e6fbb228aae45cf91219d3f98c4de2|e186f17c6c73ae7deb117a135411e38b9f2c39997d41bda5009efd33eccf3396",
        "0275e0aa689bdd20de3281f27c17f5bad6ee554373991a8140b495cf5af0585e1f|ee8f29b178fd59fab860a71519eed23e60aaf1210225823b70eb1c16d8fe0a6c",
        "029d3bae0673df9b85286b9eb7c85ecceb4ee9a4f0d51eca01053a0e089c487875|83107fc6ebb963f83d2c8668dc03f58b6c7ffbc47da190d4ed93e2c07664c7f9",
        "0271fa38e1592ad1fff9d32db643cf95c50721b2a7205642a361fd904a8b57d7e2|1a8bbb9ad0cbb0c15bcba940a77fd005268c7de10cc18d60c7590f370731a17b",
        "033f2d6bb7b22c39f432b3e6a2558420e399ba84d7709cb19acb603838e915b53f|b1cb1a81bc23c4ad6b52290228e0dd0bc09db1a69cc09d47a2490aaadfe2fce3",
        "03f3a364eaeeaceb24784f1a3dc1b6700741039f2fdfd4251214225f9747605b0b|5528921603510c1036ef9e7958dd3350fb4c57efdb3d288117f81b787aed21d3",
        "02daccf1dee6d8ff9044ddaf4497351f78023894a4fdf0de182b68ebe72488dfff|460f51ea48a89395577f3d0d50314a20dab4546f951c97be29a5622ecfe08c97",
        "03ea96bd6517e8b8e88dbec80619c606f5a932591e756322ce685427c3a1b77881|d1d0931b1bfd2029ab46725d49e3105dbcaa83004ed4d4a7a3ff6f5a30ffabe2",
        "03af21d678fc3c76211e8ae02f3a7296409e623c98d7223767d7d285ee5f3a2225|c31151f03179e883839d1899cf53b5a9c0c4f340b081235662c8e1769921b7d0",
        "03af56d0415fca642ce277e3bb4d6e53a4964829a038ec15033dbe98c9fcff4879|9267da85f43f449b24a10b873f64ddc0cfa5ea40327bf0827f7cf8122bca321b",
        "03c59114c017e763e7e7e1d2c5c9dd1d445b86a47cab35f3d79a3dbeb1e83ef51e|82cff607e31c0588efcbcd6090d9192e4a1fada0d2910b08f696896e0388be0c",
        "02a7ca54448771fbee06346c9a16c890e5e906d356844db8f86fa4a23840ca692d|abfb70c9365af82ff3177d53852d1ab811dca1c8fe7e4b129d1302b2ed4d8ed5",
        "0299522ee311585d2ce3e1b8c359dd4428326d84b136d0e112bd0a58dbc1543192|b3b563c54c0ac4209813d46eb47ff49e013b7225dc55b9a81e8c2f74c1852045",
        "02ff8c2d862b47276a40464bd1d55edf53600895059aa8791e87dd732696386aeb|4787584c5f3d361a9b64d8ebfc85e7f26ff3272f970590bfc8e26498156af08c",
        "0322f34ea037150a0bb0bdf56cd9ef640aa3d804cb4d6a1093104681c46f1a0d55|b4b3745672b2da18af65b0b4ca3ebb7c0325f9bc45c580da056f5b50cdd5c51a",
        "03a98e556f5bd370585754e4219fb15f38198f458147e4d4d00bf7f6b2023b4153|eb2784ba5c3c5f8371a71216a08303747e95257f9cb0d869f232f10ca5791181",
        "03c78cb70a09682fc9f6b19e734ad87a66676042c8d75d0dc9a06614d4fd38971b|ad981d45744340710ae5da3288c814d32b96fa2dc1727542d38e5b0198581867",
        "03032ea7898190360d0e6681f90773f9f2aa1c4b2b6b345bfa2403aa6914bfba69|72e2cff780253450e7d94800e7f84cbac4444e2cf4069fc61dad290d3e21a117",
        "02a367e29ceb50dffc2bb8b701b0fca3427e4c9cd98f3494fc75aefea78117385a|7850a44ecd6f3c265e045338f35fe46d1e7324f6eeaae4a7401208ee2594d3fc",
        "03341e207dd9eb991f70c6ef79994b383df3b530d963a89a20e65d938169ed5e19|8a901a279197a28c815ea0bfc4db5697289e3e74e410f3086f156b69dc54d9f9",
        "0265233c416552e173ee5096adfd4601c9771c9197a6e463ae1ea8aa01c302fb6d|c0df26be837feb4cb45c08241ff123775f3acb0be8ff627f29cd7dc58163d443",
        "038af6ad9a7c4ecef1e61d7861529fa8c4af4ca730553c13e114c5b51da62749d3|caae4161396782866476da8acf6b4b1ea756185fefa420326ebd772ff88215c5",
        "02e4be67de096eee89ba9128986a3f65ca9d02bcf9f700f03f543659463a0c23c4|3a78353700db50e9ea1e55bd41fe68a29959eae0dd9d247d7d9c2689e4ef81f3",
        "03ef6cf016ed7879f6eec8dab8998fd725f771dd42fceca986911d352e940da2ee|2ad8bcfc5e265562358424b1c01acf8a1867aabeec40d076ef67fab130bf35e4",
        "038eec2a82494b19c1144addfa0bc9178f999730bf30e037fc668bf85f5f8ea58e|59f1ff20d7e55c956423ede402f612e4d723d580647bb53fe22e76733f0f1673",
        "02e7849334ed29be5197f330513332c31361fb0e8c28c11cd5811731d58082c4e2|3ad0a18e78db00ef62eb07de4360020d96cc79029aea17b8a69a7951db70396a",
        "02c9361fefac221a89c0085387f4cf5b0158f0588f97c1878f25e1a85986528b5e|ab763650b3ef44a4bbf294145e10b179b74987515efa5f4ebc10c11bc4e48ce4",
        "03edbf00411d40930e1f69d97dd1841cc6dfee58fa4ad2e17522589f7bbc00b081|78af094bcc0ba081f54c45986086b53fac6655c146a61c89bd88b41ff522b65a",
        "03252801f9a7d76f4e4ded121a08468b88d4705410599afe2b0cdee5791b446cbf|b8ea7bbeaa5daeaed8516b1fc5823b2b323f587c915b34285d465d0083f5f62e",
        "02836a8c5df9b3df0cf71c0f25cf179aa8ab090fff81dcfeb18fb69ddf3026a19c|6399ec930f19c4bcccc998d2907be7d5e5a40a48c3cc5bd08058bb6da8c08295",
        "0254701403f9ea451463a26f3472a5aa55c876f5c4ca36d4057553ad585d0d1898|1628c4b66ffdf2ec5f457271904ca95c5b60a158a8951f6f1d89fb5b6113c73c",
        "022a049abc780e91e6c27336614e785408ce8a17df297956184479b10f7c5431db|8d707fb82c80ff284509ebb476993204a278d4451af89265c79fd3af82548af6",
        "0254b27fdd5629a64cc4949935dad46dcb8cd91a55141f6d238a241ec9fb474e3e|2cb74396a6c27bd9cc1c1f054fc237a4d9718d281fba5de07463e9206e274f4d",
        "03b6c5542fe76ead2ad08c1111cded4560aaf5cc08a884c8f20ddbbc21cdc80a5f|147466d069b38b8009a3e48ec13e3ed218eb9701753ca4cb8b6ecc052e2c08e3",
        "03d3b1a1cbec71f3577d4e8a427e1dc1929c2ef8016b50bc8879c5ff41d61f9953|cdad5bb349f1715253b2da8f1d078c2d845c1bf4de2b7a00cb414f7242a79fd5",
        "02e5e6e00da872cec1955b3a64ce0ad19a5440f4989b2bb72b43298be1d5ccc561|a72c429b8f379ff153d9978c657d196853d5302bc8e83aa3e20fd1e447cfd8c8",
        "022b7912769c9174157ea028c1e4a87c838a5d98904f3d6af7c440ff7fb3493bfc|5ccbc384b61617926c2cc7099f71fc471bbb53e2713bdca33a216151d2aa8783",
        "02b6c2260053a35197210d10e5514455f7e436eb70d42625d0402211e46c2c0171|cf35c942a60164b5aada1df3e5264ce4ad0d1af207267ee7368cd292b3b478df",
        "0244e0e4602e834975d3f62f6276f1d7a08d67744cb0f74035ec71ce1031206ca9|6d27004d50339b59a36786aba94fbece425db6cfb2d53caec10dff603fcf65aa",
    };
}
