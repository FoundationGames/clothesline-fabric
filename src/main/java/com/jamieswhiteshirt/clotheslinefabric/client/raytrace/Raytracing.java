package com.jamieswhiteshirt.clotheslinefabric.client.raytrace;

import com.jamieswhiteshirt.clotheslinefabric.api.NetworkEdge;
import com.jamieswhiteshirt.clotheslinefabric.api.NetworkManager;
import com.jamieswhiteshirt.clotheslinefabric.api.NetworkState;
import com.jamieswhiteshirt.clotheslinefabric.api.Path;
import com.jamieswhiteshirt.clotheslinefabric.api.util.MutableSortedIntMap;
import com.jamieswhiteshirt.clotheslinefabric.client.EdgeAttachmentProjector;
import com.jamieswhiteshirt.clotheslinefabric.client.LineProjection;
import com.jamieswhiteshirt.clotheslinefabric.client.Mat4f;
import com.jamieswhiteshirt.clotheslinefabric.client.Vec4f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Environment(EnvType.CLIENT)
public class Raytracing {
    private static final net.minecraft.util.math.Box ATTACHMENT_BOX = new net.minecraft.util.math.Box(-0.5D, -0.5D, -0.5D, 0.5D, 0.5D, 0.5D);

    @Nullable
    public static NetworkRaytraceHit raytraceNetworks(NetworkManager manager, Ray ray, double maxDistanceSq, float delta) {
        com.jamieswhiteshirt.rtree3i.Box box = com.jamieswhiteshirt.rtree3i.Box.create(
            (int) Math.floor(Math.min(ray.from.x, ray.to.x) - 0.5D),
            (int) Math.floor(Math.min(ray.from.y, ray.to.y) - 0.5D),
            (int) Math.floor(Math.min(ray.from.z, ray.to.z) - 0.5D),
            (int) Math.ceil(Math.max(ray.from.x, ray.to.x) + 0.5D),
            (int) Math.ceil(Math.max(ray.from.y, ray.to.y) + 0.5D),
            (int) Math.ceil(Math.max(ray.from.z, ray.to.z) + 0.5D)
        );

        NetworkRaytraceHit hit = null;
        List<NetworkEdge> edges = manager.getNetworks().getEdges().values(box::intersectsClosed).collect(Collectors.toList());
        for (NetworkEdge edge : edges) {
            NetworkRaytraceHit hitCandidate = raytraceEdge(ray, edge, maxDistanceSq, delta);
            if (hitCandidate != null && hitCandidate.distanceSq < maxDistanceSq) {
                maxDistanceSq = hitCandidate.distanceSq;
                hit = hitCandidate;
            }
        }

        return hit;
    }

    @Nullable
    private static NetworkRaytraceHit raytraceEdge(Ray viewRay, NetworkEdge edge, double maxDistanceSq, float delta) {
        Path.Edge pathEdge = edge.getPathEdge();
        LineProjection projection = LineProjection.create(edge);
        NetworkRaytraceHit hit = null;

        Ray edgeRay = new Ray(projection.projectRUF(-2.0D / 16.0D, 0.0D, 0.0D), projection.projectRUF(-2.0D / 16.0D, 0.0D, 1.0D));

        double b = viewRay.delta.dotProduct(edgeRay.delta);
        Vec3d w0 = viewRay.from.subtract(edgeRay.from);
        double denominator = viewRay.lengthSq * edgeRay.lengthSq - b * b;
        if (denominator != 0.0D) {
            double d = viewRay.delta.dotProduct(w0);
            double e = edgeRay.delta.dotProduct(w0);
            double viewDeltaScalar = MathHelper.clamp((b * e - edgeRay.lengthSq * d) / denominator, 0.0D, 1.0D);
            double edgeDeltaScalar = MathHelper.clamp((viewRay.lengthSq * e - b * d) / denominator, 0.0D, 1.0D);

            Vec3d viewNear = viewRay.project(viewDeltaScalar);
            Vec3d edgeNear = edgeRay.project(edgeDeltaScalar);

            Vec3d nearDelta = edgeNear.subtract(viewNear);
            if (nearDelta.lengthSquared() < (1.0D / 16.0D) * (1.0D / 16.0D)) {
                double rayLengthSquared = (viewNear.subtract(viewRay.from)).lengthSquared();
                if (rayLengthSquared < maxDistanceSq) {
                    double offset = pathEdge.getFromOffset() * (1.0D - edgeDeltaScalar) + pathEdge.getToOffset() * edgeDeltaScalar;
                    hit = new EdgeRaytraceHit(rayLengthSquared, edge, offset);
                }
            }
        }

        NetworkState state = edge.getNetwork().getState();
        double fromAttachmentKey = state.offsetToAttachmentKey(pathEdge.getFromOffset(), delta);
        double toAttachmentKey = state.offsetToAttachmentKey(pathEdge.getToOffset(), delta);
        List<MutableSortedIntMap.Entry<ItemStack>> attachments = state.getAttachmentsInRange((int) fromAttachmentKey, (int) toAttachmentKey);
        if (!attachments.isEmpty()) {
            Vec4f lFrom = new Vec4f();
            Vec4f lTo = new Vec4f();
            Vec4f wHitVec = new Vec4f();

            EdgeAttachmentProjector projector = EdgeAttachmentProjector.build(edge);
            for (MutableSortedIntMap.Entry<ItemStack> attachment : attachments) {
                double attachmentOffset = state.attachmentKeyToOffset(attachment.getKey(), delta);
                // Local space to world space matrix
                Mat4f l2w = projector.getL2WForAttachment(state.getMomentum(delta), attachmentOffset, delta);

                // World space to local space matrix
                Mat4f w2l = projector.getW2LForAttachment(state.getMomentum(delta), attachmentOffset, delta);

                lFrom.set((float) viewRay.from.x, (float) viewRay.from.y, (float) viewRay.from.z, 1.0F);
                lFrom.multiply(w2l);
                lTo.set((float) viewRay.to.x, (float) viewRay.to.y, (float) viewRay.to.z, 1.0F);
                lTo.multiply(w2l);

                Optional<Vec3d> lResult = ATTACHMENT_BOX.rayTrace(new Vec3d(lFrom.getV0(), lFrom.getV1(), lFrom.getV2()), new Vec3d(lTo.getV0(), lTo.getV1(), lTo.getV2()));
                if (lResult.isPresent()) {
                    Vec3d lHitVec = lResult.get();
                    wHitVec.set((float) lHitVec.x, (float) lHitVec.y, (float) lHitVec.z, 1.0F);
                    wHitVec.multiply(l2w);
                    double distanceSq = new Vec3d(wHitVec.getV0(), wHitVec.getV1(), wHitVec.getV2()).squaredDistanceTo(viewRay.from);
                    if (distanceSq < maxDistanceSq) {
                        maxDistanceSq = distanceSq;
                        hit = new AttachmentRaytraceHit(distanceSq, edge, attachment.getKey(), l2w);
                    }
                }
            }
        }

        return hit;
    }
}
