package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.lod.Blake3;
import me.cortex.voxy.client.lod.CatalogCodec;
import me.cortex.voxy.client.lod.ContentPipeline.ModelClass;
import me.cortex.voxy.client.lod.ContentPipeline.ModelCompatibility;
import me.cortex.voxy.client.lod.ContentPipeline.RendererIdentity;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Conservative catalog plus baked-model classifier for the hybrid meshing fast path. */
public final class CatalogModelCompatibility implements ModelCompatibility {
    private static final byte[] FINGERPRINT_DOMAIN =
            "Voxy resource and baked model fingerprint\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESOURCE_DOMAIN =
            "Voxy baked model resource state\0".getBytes(StandardCharsets.UTF_8);

    private final ModelFactory models;
    private final boolean[] catalogAuthoritative;
    private final Set<Integer> safeTemplates;
    private final RendererIdentity rendererIdentity;

    public CatalogModelCompatibility(CatalogCodec.Catalog catalog, int[] blockTranslations,
                                int[] biomeTranslations,
                                ModelFactory models, Collection<Integer> safeTemplateBlockIds,
                                Hash256 resourcePackFingerprint) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(blockTranslations, "blockTranslations");
        Objects.requireNonNull(biomeTranslations, "biomeTranslations");
        this.models = Objects.requireNonNull(models, "models");
        this.safeTemplates = Set.copyOf(Objects.requireNonNull(
                safeTemplateBlockIds, "safeTemplateBlockIds"));
        Objects.requireNonNull(resourcePackFingerprint, "resourcePackFingerprint");
        if (blockTranslations.length != catalog.blocks().size()
                || biomeTranslations.length != catalog.biomes().size()) {
            throw new IllegalArgumentException("catalog and local translations disagree");
        }
        int maximumLocal = 0;
        for (int local : blockTranslations) {
            if (local < 0) continue;
            maximumLocal = Math.max(maximumLocal, local);
        }
        this.catalogAuthoritative = new boolean[maximumLocal + 1];
        Arrays.fill(this.catalogAuthoritative, true);
        boolean[] seen = new boolean[this.catalogAuthoritative.length];
        for (int remote = 0; remote < blockTranslations.length; remote++) {
            int local = blockTranslations[remote];
            if (local < 0) continue;
            boolean authoritative = catalog.blocks().get(remote).authoritative();
            if (!seen[local]) {
                seen[local] = true;
                this.catalogAuthoritative[local] = authoritative;
            } else {
                this.catalogAuthoritative[local] &= authoritative;
            }
        }
        Hash256 bakedFingerprint = fingerprint(catalog, blockTranslations, biomeTranslations,
                resourcePackFingerprint);
        this.rendererIdentity = new RendererIdentity(bakedFingerprint);
    }

    /** Creates the conservative classifier for this renderer session. */
    public static CatalogModelCompatibility create(CatalogCodec.Catalog catalog,
                                               int[] blockTranslations,
                                               int[] biomeTranslations,
                                               ModelFactory models) {
        Objects.requireNonNull(models, "models");
        return new CatalogModelCompatibility(catalog, blockTranslations, biomeTranslations, models,
                // A baked model being opaque and non-cubic does not prove that the generic GPU
                // template path reproduces it. Only an explicit, independently validated
                // template registry may populate this set; unknown shapes stay on the CPU path.
                Set.of(),
                Hash256.fromBytes(new Blake3.Hasher().update(RESOURCE_DOMAIN).digest()));
    }

    @Override
    public ModelClass classify(int localBlockId) {
        if (localBlockId == 0) return ModelClass.AIR;
        if (localBlockId < 0 || localBlockId >= this.catalogAuthoritative.length
                || !this.catalogAuthoritative[localBlockId]
                || !this.models.hasModelForBlockId(localBlockId)) {
            return ModelClass.UNKNOWN;
        }
        int modelId = this.models.getModelId(localBlockId);
        long metadata = this.models.getModelMetadataFromClientId(modelId);
        if (ModelQueries.containsFluid(metadata) || ModelQueries.isFluid(metadata)
                || ModelQueries._isTranslucent(metadata) != 0
                || ModelQueries._isDoubleSided(metadata) != 0) {
            return ModelClass.COMPLEX;
        }
        if (this.safeTemplates.contains(localBlockId)) return ModelClass.SAFE_TEMPLATE;
        if (!ModelQueries.isFullyOpaque(metadata)) return ModelClass.COMPLEX;
        for (int face = 0; face < 6; face++) {
            if (!ModelQueries.faceExists(metadata, face)
                    || !ModelQueries.faceOccludes(metadata, face)) {
                return ModelClass.COMPLEX;
            }
        }
        return ModelClass.SAFE_OPAQUE_CUBE;
    }

    @Override
    public boolean ready(int localBlockId) {
        return localBlockId == 0 || localBlockId > 0
                && localBlockId < this.catalogAuthoritative.length
                && this.models.isModelReadyForBlockId(localBlockId);
    }

    public RendererIdentity rendererIdentity() {
        return this.rendererIdentity;
    }

    private Hash256 fingerprint(CatalogCodec.Catalog catalog, int[] translations,
                                int[] biomeTranslations,
                                Hash256 resourcePackFingerprint) {
        Blake3.Hasher hasher = new Blake3.Hasher().update(FINGERPRINT_DOMAIN)
                .update(resourcePackFingerprint.toBytes());
        ByteBuffer header = ByteBuffer.allocate(Long.BYTES * 3 + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(catalog.catalogId()).putLong(catalog.generation())
                .putLong(catalog.mipGeneration()).putInt(catalog.blocks().size());
        hasher.update(header.array());
        for (int remote = 0; remote < catalog.blocks().size(); remote++) {
            CatalogCodec.Block block = catalog.blocks().get(remote);
            int local = translations[remote];
            byte[] canonical = block.canonical().getBytes(StandardCharsets.UTF_8);
            ByteBuffer scalar = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(remote).putInt(local).putInt(canonical.length)
                    .putInt(block.opacity())
                    .put((byte) (block.authoritative() ? 1 : 0))
                    .put((byte) 0).putShort((short) 0);
            hasher.update(scalar.array()).update(canonical);
        }
        hasher.update(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(catalog.biomes().size()).array());
        for (int remote = 0; remote < catalog.biomes().size(); remote++) {
            byte[] canonical = catalog.biomes().get(remote).getBytes(StandardCharsets.UTF_8);
            hasher.update(ByteBuffer.allocate(Integer.BYTES * 3).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(remote).putInt(biomeTranslations[remote])
                    .putInt(canonical.length).array()).update(canonical);
        }
        return Hash256.fromBytes(hasher.digest());
    }
}
