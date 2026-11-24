package com.storagehub.service;

import com.storagehub.api.facility.RackLayout;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.PalletPlan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class PalletPacking {
    record Cargo(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg,
                 Integer maxLayers, boolean allowHorizontalPlacement) {
        static Cargo from(GoodsItemRequest item) {
            Integer maxLayers;
            if (item.isFragile()) {
                // Fragile goods never become stackable just because a client
                // also sent stackable=true. The safety restriction wins.
                maxLayers = 1;
            } else if (item.hasStackabilityDeclaration()) {
                maxLayers = item.allowsStacking() ? null : 1;
            } else {
                // Keep old quote snapshots and old clients working until
                // their declaration is renewed with the stackable flag.
                maxLayers = item.isFragile() ? 1 : item.getMaxStackLayers();
            }
            return new Cargo(item.getLengthCm(), item.getWidthCm(), item.getHeightCm(), item.getWeightPerItemKg(),
                maxLayers, item.allowsHorizontalPlacement());
        }

        static Cargo from(RackLayout.Sample sample) {
            return new Cargo(BigDecimal.valueOf(sample.lengthCm()), BigDecimal.valueOf(sample.widthCm()),
                BigDecimal.valueOf(sample.heightCm()), BigDecimal.valueOf(sample.weightKg()), sample.maxLayers(), false);
        }
    }

    private record Placement(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}
    private record PlacementCapacity(Placement placement, int piecesPerLayer, int usableLayers,
                                     int maxPiecesByDimensions, int maxPiecesByWeight, int maxPieces) {}

    record Result(PalletPlan plan, String issue, Integer requiredPallets, Integer availableSlots) {
        Result(PalletPlan plan, String issue) {
            this(plan, issue, null, null);
        }
    }

    private PalletPacking() {}

    static PalletPlan.Capacity palletCapacity(RackLayout.Pallet pallet, Cargo cargo, BigDecimal cargoHeightCm,
                                              BigDecimal maxLoadedKg) {
        BigDecimal cargoWeightLimit = maxLoadedKg.subtract(pallet.emptyKg()).min(pallet.maxPayloadKg()).max(BigDecimal.ZERO);
        return capacity(pallet, cargo, cargoHeightCm, cargoWeightLimit, maxLoadedKg);
    }

    private static PalletPlan.Capacity palletCapacityForAvailableCargo(RackLayout.Pallet pallet, Cargo cargo,
                                                                         BigDecimal cargoHeightCm,
                                                                         BigDecimal availableCargoKg) {
        BigDecimal cargoWeightLimit = availableCargoKg.min(pallet.maxPayloadKg()).max(BigDecimal.ZERO);
        return capacity(pallet, cargo, cargoHeightCm, cargoWeightLimit, pallet.emptyKg().add(cargoWeightLimit));
    }

    private static PalletPlan.Capacity capacity(RackLayout.Pallet pallet, Cargo cargo, BigDecimal cargoHeightCm,
                                                BigDecimal cargoWeightLimit, BigDecimal maxLoadedKg) {
        PlacementCapacity selected = placementCapacities(pallet, cargo, cargoHeightCm, cargoWeightLimit).stream()
            .max(Comparator.comparingInt(PlacementCapacity::maxPieces)
                .thenComparingInt(PlacementCapacity::maxPiecesByDimensions)
                .thenComparingInt(PlacementCapacity::piecesPerLayer)
                .thenComparingInt(value -> -value.placement().heightCm().intValue()))
            .orElseThrow();
        return new PalletPlan.Capacity(maxLoadedKg, cargoWeightLimit, selected.piecesPerLayer(), selected.usableLayers(),
            selected.maxPiecesByDimensions(), selected.maxPiecesByWeight(), selected.maxPieces(),
            selected.placement().lengthCm(), selected.placement().widthCm(), selected.placement().heightCm());
    }

    private static List<PlacementCapacity> placementCapacities(RackLayout.Pallet pallet, Cargo cargo,
                                                                BigDecimal cargoHeightCm,
                                                                BigDecimal cargoWeightLimit) {
        int maxByWeight = floor(cargoWeightLimit, cargo.weightKg(), Integer.MAX_VALUE);
        List<PlacementCapacity> result = new ArrayList<>();
        for (Placement placement : placements(cargo)) {
            int layers = floor(cargoHeightCm, placement.heightCm(),
                cargo.maxLayers() == null ? Integer.MAX_VALUE : cargo.maxLayers());
            int piecesPerLayer = (int) Math.min(Integer.MAX_VALUE,
                grid(pallet, placement.lengthCm(), placement.widthCm()));
            int maxByDimensions = (int) Math.min(Integer.MAX_VALUE, (long) piecesPerLayer * layers);
            result.add(new PlacementCapacity(placement, piecesPerLayer, layers, maxByDimensions,
                maxByWeight, Math.min(maxByDimensions, maxByWeight)));
        }
        return result;
    }

    private static List<Placement> placements(Cargo cargo) {
        List<Placement> candidates = new ArrayList<>();
        addPlacement(candidates, cargo.lengthCm(), cargo.widthCm(), cargo.heightCm());
        addPlacement(candidates, cargo.widthCm(), cargo.lengthCm(), cargo.heightCm());
        if (cargo.allowHorizontalPlacement()) {
            addPlacement(candidates, cargo.lengthCm(), cargo.heightCm(), cargo.widthCm());
            addPlacement(candidates, cargo.heightCm(), cargo.lengthCm(), cargo.widthCm());
            addPlacement(candidates, cargo.widthCm(), cargo.heightCm(), cargo.lengthCm());
            addPlacement(candidates, cargo.heightCm(), cargo.widthCm(), cargo.lengthCm());
        }
        return candidates;
    }

    private static void addPlacement(List<Placement> placements, BigDecimal lengthCm, BigDecimal widthCm,
                                     BigDecimal heightCm) {
        boolean duplicate = placements.stream().anyMatch(value -> value.lengthCm().compareTo(lengthCm) == 0
            && value.widthCm().compareTo(widthCm) == 0
            && value.heightCm().compareTo(heightCm) == 0);
        if (!duplicate) placements.add(new Placement(lengthCm, widthCm, heightCm));
    }

    static int floor(BigDecimal available, BigDecimal perPiece, int ceiling) {
        return available.max(BigDecimal.ZERO).divideToIntegralValue(perPiece)
            .min(BigDecimal.valueOf(ceiling)).intValueExact();
    }

    private static long grid(RackLayout.Pallet pallet, BigDecimal length, BigDecimal width) {
        return (long) floor(BigDecimal.valueOf(pallet.lengthCm()), length, Integer.MAX_VALUE)
            * floor(BigDecimal.valueOf(pallet.widthCm()), width, Integer.MAX_VALUE);
    }

    static Result fit(RackLayout.Result layout, List<GoodsItemRequest> items) {
        List<CargoLine> lines = items.stream()
            .map(item -> new CargoLine(Cargo.from(item), item.getQuantity()))
            .toList();
        return layout.configuration().autoGeneratePallet()
            ? fitCargo3d(layout, lines)
            : fitCargo(layout, lines);
    }

    static PalletPlan planSample(RackLayout.Result layout, RackLayout.Sample sample, int quantity) {
        if (quantity == 0) return null;
        Result result = layout.configuration().autoGeneratePallet()
            ? fitCargo3d(layout, List.of(new CargoLine(Cargo.from(sample), quantity)))
            : fitCargo(layout, List.of(new CargoLine(Cargo.from(sample), quantity)));
        return result.plan();
    }

    static Result planMixedSample(RackLayout.Result layout, int smallQuantity, int largeQuantity) {
        List<CargoLine> lines = List.of(
            new CargoLine(Cargo.from(layout.smallBox()), smallQuantity),
            new CargoLine(Cargo.from(layout.largeBox()), largeQuantity));
        return layout.configuration().autoGeneratePallet()
            ? fitCargo3d(layout, lines)
            : fitCargo(layout, lines);
    }

    /**
     * Heuristic 3D bin packing used by v8 layouts.  The old line-by-line
     * allocator is deliberately retained for legacy snapshots so old quotes
     * remain reproducible.  A v8 pallet is a real shared container: different
     * goods lines may occupy the same pallet and every placed box gets an
     * explicit x, y, z position in the response.
     */
    private static Result fitCargo3d(RackLayout.Result layout, List<CargoLine> items) {
        RackLayout.Pallet pallet = layout.configuration().pallet();
        BigDecimal cargoWeightKg = items.stream()
            .map(item -> item.cargo.weightKg().multiply(BigDecimal.valueOf(item.quantity)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cargoWeightKg.compareTo(layout.unitCargoLimitKg()) > 0) {
            return new Result(null, "Đơn nặng " + displayNumber(cargoWeightKg) + " kg, vượt "
                + displayNumber(cargoWeightKg.subtract(layout.unitCargoLimitKg()))
                + " kg so với giới hạn " + displayNumber(layout.unitCargoLimitKg()) + " kg hàng của gian.");
        }

        List<RackSpace> racks = new ArrayList<>();
        for (int i = 0; i < layout.racks().size(); i++) {
            racks.add(new RackSpace(layout.racks().get(i), layout.configuration().racks().get(i), pallet));
        }
        List<Line3d> lines = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            CargoLine line = items.get(index);
            if (line.quantity > 0) lines.add(new Line3d(index + 1, line.quantity, line.cargo));
        }
        lines.sort(Comparator
            .comparing((Line3d line) -> line.cargo.lengthCm().multiply(line.cargo.widthCm()).multiply(line.cargo.heightCm()),
                Comparator.reverseOrder())
            .thenComparing(Line3d::weightKg, Comparator.reverseOrder())
            .thenComparingInt(Line3d::itemNumber));

        List<PalletState> states = new ArrayList<>();
        int nextPalletNumber = 1;
        for (Line3d line : lines) {
            for (int boxNumber = 1; boxNumber <= line.quantity; boxNumber++) {
                PlacementChoice choice = bestExistingPlacement(states, line);
                if (choice != null) {
                    choice.state.place(line, boxNumber, choice);
                    continue;
                }

                PalletState newState = null;
                PlacementChoice newChoice = null;
                for (RackSpace rack : racks) {
                    for (CompartmentSpace compartment : rack.compartments) {
                        if (compartment.freeSlots <= 0) continue;
                        PalletState candidateState = new PalletState(nextPalletNumber, rack, compartment, pallet,
                            layout.racks().get(racks.indexOf(rack)).maxCargoHeightCm());
                        PlacementChoice candidateChoice = bestPlacement(candidateState, line);
                        if (candidateChoice == null) continue;
                        if (candidateChoice.state.availableCargoKg().compareTo(line.cargo.weightKg()) < 0) continue;
                        newState = candidateState;
                        newChoice = candidateChoice;
                        break;
                    }
                    if (newState != null) break;
                }
                if (newState == null || newChoice == null) {
                    String issue = packingIssue3d(layout, line, racks, states, line.quantity - boxNumber + 1);
                    return new Result(null, issue, states.size() + 1, layout.palletSlotCount());
                }
                states.add(newState);
                nextPalletNumber++;
                newState.place(line, boxNumber, newChoice);
            }
        }

        List<PalletPlan.Pallet> palletPlans = states.stream()
            .map(PalletPacking::toPalletPlan)
            .toList();
        List<PalletPlan.Item> planItems = new ArrayList<>();
        for (Line3d line : lines.stream().sorted(Comparator.comparingInt(Line3d::itemNumber)).toList()) {
            List<PalletState> itemStates = states.stream()
                .filter(state -> state.itemQuantity(line.itemNumber) > 0)
                .toList();
            List<PalletPlan.Allocation> allocations = itemStates.stream()
                .map(state -> allocationFor(state, line))
                .toList();
            int largest = itemStates.stream().mapToInt(state -> state.itemQuantity(line.itemNumber)).max().orElse(0);
            int allowedLayers = itemStates.stream()
                .mapToInt(state -> theoreticalCapacity(state, line).usableLayers())
                .max()
                .orElse(line.cargo.maxLayers() == null ? 1 : line.cargo.maxLayers());
            planItems.add(new PalletPlan.Item(line.itemNumber, line.quantity, itemStates.size(), largest,
                allowedLayers, line.cargo.weightKg(), allocations));
        }
        List<PalletPlan.Rack> usedRacks = racks.stream()
            .filter(rack -> rack.usedSlots > 0)
            .map(rack -> new PalletPlan.Rack(rack.preview.code(), rack.usedSlots, rack.preview.palletSlotCount(),
                rack.loadedCargoKg.add(pallet.emptyKg().multiply(BigDecimal.valueOf(rack.usedSlots))),
                rack.preview.effectiveRackLoadKg(), rack.preview.maxBeamPairLoadKg(), rack.preview.compartmentCount(),
                rack.preview.bayLengthCm(), rack.preview.bayWidthCm(), rack.preview.maxCargoHeightCm(), rack.preview.levels()))
            .toList();
        int used = states.size();
        return new Result(new PalletPlan(pallet.lengthCm(), pallet.widthCm(), pallet.heightCm(), pallet.emptyKg(), pallet.maxPayloadKg(),
            used, layout.palletSlotCount(), cargoWeightKg.add(pallet.emptyKg().multiply(BigDecimal.valueOf(used))),
            List.copyOf(planItems), usedRacks, palletPlans), null, used, layout.palletSlotCount());
    }

    private static PlacementChoice bestExistingPlacement(List<PalletState> states, Line3d line) {
        return states.stream()
            .map(state -> bestPlacement(state, line))
            .filter(choice -> choice != null && choice.state.availableCargoKg().compareTo(line.cargo.weightKg()) >= 0)
            .min(Comparator.comparing((PlacementChoice choice) -> choice.z)
                .thenComparing(choice -> choice.y)
                .thenComparing(choice -> choice.x)
                .thenComparing(choice -> choice.state.number))
            .orElse(null);
    }

    private static PlacementChoice bestPlacement(PalletState state, Line3d line) {
        if (state.availableCargoKg().compareTo(line.cargo.weightKg()) < 0) return null;
        List<PlacementChoice> choices = new ArrayList<>();
        for (Placement placement : placements(line.cargo)) {
            for (BigDecimal x : axisCandidates(state.boxes, true)) {
                for (BigDecimal y : axisCandidates(state.boxes, false)) {
                    for (BigDecimal z : axisCandidates(state.boxes, null)) {
                        if (!fits(state, line, placement, x, y, z)) continue;
                        int layer = layerFor(state, line.itemNumber, z);
                        choices.add(new PlacementChoice(state, placement, x, y, z, layer));
                    }
                }
            }
        }
        return choices.stream()
            .min(Comparator.comparing((PlacementChoice choice) -> choice.z)
                .thenComparing(choice -> choice.y)
                .thenComparing(choice -> choice.x)
                .thenComparing(choice -> choice.placement.heightCm())
                .thenComparing(choice -> choice.placement.lengthCm().multiply(choice.placement.widthCm())))
            .orElse(null);
    }

    private static List<BigDecimal> axisCandidates(List<PlacedBox> boxes, Boolean xAxis) {
        List<BigDecimal> values = new ArrayList<>(List.of(BigDecimal.ZERO));
        for (PlacedBox box : boxes) {
            BigDecimal value = xAxis == null
                ? box.z.add(box.height)
                : xAxis ? box.x.add(box.length) : box.y.add(box.width);
            if (values.stream().noneMatch(existing -> existing.compareTo(value) == 0)) values.add(value);
        }
        return values;
    }

    private static boolean fits(PalletState state, Line3d line, Placement placement,
                               BigDecimal x, BigDecimal y, BigDecimal z) {
        if (x.signum() < 0 || y.signum() < 0 || z.signum() < 0
            || x.add(placement.lengthCm()).compareTo(BigDecimal.valueOf(state.pallet.lengthCm())) > 0
            || y.add(placement.widthCm()).compareTo(BigDecimal.valueOf(state.pallet.widthCm())) > 0
            || z.add(placement.heightCm()).compareTo(state.cargoHeightCm) > 0) return false;
        if (z.signum() > 0) {
            if (line.cargo.maxLayers() != null && line.cargo.maxLayers() <= 1) return false;
            if (!hasSupport(state, line, placement, x, y, z)) return false;
            if (line.cargo.maxLayers() != null && layerFor(state, line.itemNumber, z) > line.cargo.maxLayers()) return false;
        }
        return state.boxes.stream().noneMatch(box -> overlaps(box, placement, x, y, z));
    }

    private static boolean overlaps(PlacedBox box, Placement placement,
                                    BigDecimal x, BigDecimal y, BigDecimal z) {
        return x.compareTo(box.x.add(box.length)) < 0 && x.add(placement.lengthCm()).compareTo(box.x) > 0
            && y.compareTo(box.y.add(box.width)) < 0 && y.add(placement.widthCm()).compareTo(box.y) > 0
            && z.compareTo(box.z.add(box.height)) < 0 && z.add(placement.heightCm()).compareTo(box.z) > 0;
    }

    private static boolean hasSupport(PalletState state, Line3d line, Placement placement,
                                     BigDecimal x, BigDecimal y, BigDecimal z) {
        for (PlacedBox support : state.boxes) {
            if (support.z.add(support.height).compareTo(z) != 0) continue;
            boolean covers = support.x.compareTo(x) <= 0
                && support.x.add(support.length).compareTo(x.add(placement.lengthCm())) >= 0
                && support.y.compareTo(y) <= 0
                && support.y.add(support.width).compareTo(y.add(placement.widthCm())) >= 0;
            if (covers && support.cargo.maxLayers() != null && support.cargo.maxLayers() <= 1) return false;
            if (covers) return true;
        }
        return false;
    }

    private static int layerFor(PalletState state, int itemNumber, BigDecimal z) {
        return (int) state.boxes.stream().filter(box -> box.itemNumber == itemNumber)
            .map(box -> box.z).distinct().filter(level -> level.compareTo(z) < 0).count() + 1;
    }

    private static PalletPlan.Pallet toPalletPlan(PalletState state) {
        Map<Integer, List<PlacedBox>> byItem = new java.util.LinkedHashMap<>();
        for (PlacedBox box : state.boxes) byItem.computeIfAbsent(box.itemNumber, ignored -> new ArrayList<>()).add(box);
        List<PalletPlan.ItemAllocation> itemAllocations = byItem.entrySet().stream()
            .map(entry -> new PalletPlan.ItemAllocation(entry.getKey(), entry.getValue().size(),
                entry.getValue().stream().mapToInt(box -> box.layer).max().orElse(1),
                entry.getValue().stream().map(box -> box.cargo.weightKg())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)))
            .toList();
        List<PalletPlan.BoxPlacement> placements = state.boxes.stream()
            .map(box -> new PalletPlan.BoxPlacement(box.itemNumber, box.boxNumber, box.layer, box.x, box.y, box.z,
                box.length, box.width, box.height))
            .toList();
        return new PalletPlan.Pallet(state.number, state.rack.preview.code(), state.compartment.number,
            state.compartment.cargoCapacityKg, state.cargoWeightKg,
            state.pallet.emptyKg().add(state.cargoWeightKg), itemAllocations, placements);
    }

    private static PalletPlan.Allocation allocationFor(PalletState state, Line3d line) {
        int pieces = state.itemQuantity(line.itemNumber);
        // The capacity in an allocation describes what one pallet could hold,
        // not only what happened to be placed on this particular pallet.  The
        // old code used itemLayers() here, so a pallet carrying one layer was
        // reported as a one-layer pallet even when the compartment had room
        // for more layers.  That made the customer explanation disagree with
        // the 3D packing rules.
        PalletPlan.Capacity capacity = theoreticalCapacity(state, line);
        return new PalletPlan.Allocation(state.rack.preview.code(), state.compartment.number, 1,
            state.compartment.cargoCapacityKg, pieces, state.pallet.emptyKg().add(state.cargoWeightKg), capacity);
    }

    private static PalletPlan.Capacity theoreticalCapacity(PalletState state, Line3d line) {
        BigDecimal cargoWeightLimit = state.compartment.cargoCapacityKg
            .min(state.pallet.maxPayloadKg()).max(BigDecimal.ZERO);
        PalletPlan.Capacity best = palletCapacityForAvailableCargo(state.pallet, line.cargo, state.cargoHeightCm,
            cargoWeightLimit);
        PlacedBox placed = state.boxes.stream()
            .filter(box -> box.itemNumber == line.itemNumber)
            .findFirst()
            .orElse(null);
        if (placed == null) return best;
        PlacementCapacity actualPlacement = placementCapacities(state.pallet, line.cargo, state.cargoHeightCm,
                cargoWeightLimit).stream()
            .filter(candidate -> candidate.placement().lengthCm().compareTo(placed.length) == 0
                && candidate.placement().widthCm().compareTo(placed.width) == 0
                && candidate.placement().heightCm().compareTo(placed.height) == 0)
            .findFirst()
            .orElse(null);
        if (actualPlacement == null) return best;
        return new PalletPlan.Capacity(state.pallet.emptyKg().add(cargoWeightLimit), cargoWeightLimit,
            actualPlacement.piecesPerLayer(), actualPlacement.usableLayers(), actualPlacement.maxPiecesByDimensions(),
            actualPlacement.maxPiecesByWeight(), actualPlacement.maxPieces(), placed.length, placed.width, placed.height);
    }

    private static String packingIssue3d(RackLayout.Result layout, Line3d line,
                                         List<RackSpace> racks, List<PalletState> states, int remainingQuantity) {
        boolean geometry = racks.stream().anyMatch(rack -> rack.compartments.stream()
            .anyMatch(compartment -> placements(line.cargo).stream().anyMatch(placement -> fits(
                new PalletState(0, rack, compartment, layout.configuration().pallet(),
                    rack.preview.maxCargoHeightCm()), line, placement,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))));
        if (!geometry) return noPalletIssue(layout.configuration().pallet(), line.itemNumber, line.cargo, racks);
        int freeSlots = racks.stream().mapToInt(rack -> rack.freeSlots).sum();
        return "Không đủ vị trí tấm kê hoặc tải trọng còn lại để xếp hàng " + line.itemNumber
            + ". Thuật toán xếp ba chiều đã thử các hướng đặt được phép nhưng còn "
            + displayNumber(BigDecimal.valueOf(remainingQuantity)) + " kiện chưa được xếp. Còn " + freeSlots + " vị trí tấm kê trống.";
    }

    private static Result fitCargo(RackLayout.Result layout, List<CargoLine> items) {
        RackLayout.Pallet pallet = layout.configuration().pallet();
        BigDecimal emptyPalletKg = pallet.emptyKg();
        BigDecimal cargoWeightKg = items.stream()
            .map(item -> item.cargo.weightKg().multiply(BigDecimal.valueOf(item.quantity)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cargoWeightKg.compareTo(layout.unitCargoLimitKg()) > 0) {
            return new Result(null, "Đơn nặng " + displayNumber(cargoWeightKg) + " kg, vượt "
                + displayNumber(cargoWeightKg.subtract(layout.unitCargoLimitKg()))
                + " kg so với giới hạn " + displayNumber(layout.unitCargoLimitKg()) + " kg hàng của gian.");
        }
        List<RackSpace> racks = new ArrayList<>();
        for (int i = 0; i < layout.racks().size(); i++) {
            racks.add(new RackSpace(layout.racks().get(i), layout.configuration().racks().get(i), pallet));
        }

        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            Cargo cargo = item.cargo;
            List<Option> options = new ArrayList<>();
            for (RackSpace rack : racks) {
                for (CompartmentSpace compartment : rack.compartments) {
                    var limits = palletCapacityForAvailableCargo(pallet, cargo, rack.preview.maxCargoHeightCm(),
                        rack.availableCargoKg(compartment));
                    int capacity = Math.min(limits.maxPieces(), item.quantity);
                    if (capacity > 0) options.add(new Option(compartment, capacity, limits));
                }
            }
            if (options.isEmpty()) {
                return new Result(null, noPalletIssue(pallet, i + 1, cargo, racks));
            }
            options.sort(Comparator.comparingInt(Option::capacity).reversed());
            lines.add(new Line(i + 1, item.quantity, cargo, options));
        }

        // Keep each goods line on separate pallets and place the most constrained lines first.
        lines.sort(Comparator.comparingInt((Line line) -> line.options.size())
            .thenComparing(line -> line.cargo.weightKg(), Comparator.reverseOrder())
            .thenComparingInt(Line::itemNumber));
        List<PalletPlan.Item> planItems = new ArrayList<>();
        for (Line line : lines) {
            int remaining = line.quantity;
            int used = 0;
            int largestPallet = 0;
            List<PalletPlan.Allocation> allocations = new ArrayList<>();
            for (Option option : line.options) {
                if (remaining == 0) break;
                CompartmentSpace compartment = option.compartment;
                RackSpace rack = compartment.rack;
                BigDecimal weight = line.cargo.weightKg();
                while (remaining > 0 && compartment.freeSlots > 0) {
                    var limits = palletCapacityForAvailableCargo(pallet, line.cargo,
                        rack.preview.maxCargoHeightCm(), rack.availableCargoKg(compartment));
                    int pieces = Math.min(remaining, limits.maxPieces());
                    if (pieces <= 0) break;
                    BigDecimal cargoLoad = weight.multiply(BigDecimal.valueOf(pieces));
                    BigDecimal loadedPallet = emptyPalletKg.add(cargoLoad);
                    rack.load(compartment, cargoLoad);
                    allocations.add(new PalletPlan.Allocation(rack.preview.code(), compartment.number, 1,
                        compartment.cargoCapacityKg, pieces, loadedPallet, limits));
                    remaining -= pieces;
                    used++;
                    largestPallet = Math.max(largestPallet, pieces);
                }
            }
            if (remaining > 0) {
                Option bestOption = line.options.stream()
                    .max(Comparator.comparingInt(option -> option.limits.maxPieces()))
                    .orElse(null);
                if (bestOption != null) {
                    var capacity = bestOption.limits;
                    int maxPiecesPerPallet = capacity.maxPieces();
                    int additionalPallets = (remaining + maxPiecesPerPallet - 1) / maxPiecesPerPallet;
                    int freeSlots = racks.stream().mapToInt(rack -> rack.freeSlots).sum();
                    int totalRequiredForLine = used + additionalPallets;
                    int totalSlotsForLine = used + freeSlots;
                    String placement = displayNumber(capacity.placementLengthCm()) + " × "
                        + displayNumber(capacity.placementWidthCm()) + " × "
                        + displayNumber(capacity.placementHeightCm()) + " cm";
                    String stacking = capacity.usableLayers() == 1
                        ? "1 lớp"
                        : displayNumber(BigDecimal.valueOf(capacity.usableLayers())) + " lớp";
                    return new Result(null, "Không đủ vị trí tấm kê để xếp hàng " + line.itemNumber + ". "
                        + "Theo hướng đặt " + placement + ", mỗi tấm kê chỉ chứa tối đa "
                        + maxPiecesPerPallet + " kiện (" + capacity.piecesPerLayer() + " kiện mỗi lớp, "
                        + stacking + "). Dòng này cần tổng cộng " + totalRequiredForLine
                        + " vị trí tấm kê nhưng phần còn lại chỉ đáp ứng " + totalSlotsForLine
                        + " vị trí. Cần thêm " + additionalPallets + " vị trí nhưng chỉ còn " + freeSlots
                        + ". Còn "
                        + remaining + " kiện ở dòng " + line.itemNumber + " chưa được xếp.",
                        totalRequiredForLine, totalSlotsForLine);
                }
                return new Result(null, "Chưa tìm được cách xếp toàn bộ hàng vào số vị trí và giới hạn tải còn lại của các khung. Còn "
                    + remaining + " kiện ở dòng " + line.itemNumber + ".");
            }
            int allowedLayers = allocations.stream()
                .mapToInt(allocation -> allocation.capacity().usableLayers())
                .max()
                .orElse(line.cargo.maxLayers() == null ? 1 : line.cargo.maxLayers());
            planItems.add(new PalletPlan.Item(line.itemNumber, line.quantity, used, largestPallet,
                allowedLayers, line.cargo.weightKg(), List.copyOf(allocations)));
        }
        planItems.sort(Comparator.comparingInt(PalletPlan.Item::itemNumber));
        var placed = racks.stream().filter(rack -> rack.freeSlots < rack.preview.palletSlotCount())
            .map(rack -> new PalletPlan.Rack(rack.preview.code(), rack.preview.palletSlotCount() - rack.freeSlots,
                rack.preview.palletSlotCount(), rack.loadedCargoKg.add(emptyPalletKg.multiply(BigDecimal.valueOf(rack.usedSlots))),
                rack.preview.effectiveRackLoadKg(), rack.preview.maxBeamPairLoadKg(), rack.preview.compartmentCount(),
                rack.preview.bayLengthCm(), rack.preview.bayWidthCm(), rack.preview.maxCargoHeightCm(), rack.preview.levels())).toList();
        int used = planItems.stream().mapToInt(PalletPlan.Item::requiredPallets).sum();
        return new Result(new PalletPlan(pallet.lengthCm(), pallet.widthCm(), pallet.heightCm(), emptyPalletKg, pallet.maxPayloadKg(), used, layout.palletSlotCount(),
            cargoWeightKg.add(emptyPalletKg.multiply(BigDecimal.valueOf(used))), List.copyOf(planItems), placed, List.of()), null,
            used, layout.palletSlotCount());
    }

    private record CargoLine(Cargo cargo, int quantity) {}
    private record Line3d(int itemNumber, int quantity, Cargo cargo) {
        BigDecimal weightKg() { return cargo.weightKg(); }
    }
    private record Line(int itemNumber, int quantity, Cargo cargo, List<Option> options) {}
    private record Option(CompartmentSpace compartment, int capacity, PalletPlan.Capacity limits) {}

    private record PlacementChoice(PalletState state, Placement placement,
                                   BigDecimal x, BigDecimal y, BigDecimal z, int layer) {}

    private record PlacedBox(int itemNumber, int boxNumber, int layer,
                             BigDecimal x, BigDecimal y, BigDecimal z,
                             BigDecimal length, BigDecimal width, BigDecimal height,
                             Cargo cargo) {}

    private static String footprintIssue(RackLayout.Pallet pallet, int itemNumber, Cargo cargo) {
        if (placements(cargo).stream().anyMatch(placement -> grid(pallet, placement.lengthCm(), placement.widthCm()) > 0)) return null;
        String prefix = "Hàng " + itemNumber + " không vừa mặt pallet. ";
        if (cargo.allowHorizontalPlacement()) {
            return prefix + "Không có hướng đặt được phép nào để đặt đủ ba chiều của kiện lên mặt pallet."
                + " Kích thước mặt pallet là " + pallet.lengthCm() + " × " + pallet.widthCm() + " cm.";
        }
        boolean lengthIsLonger = cargo.lengthCm().compareTo(cargo.widthCm()) >= 0;
        BigDecimal longest = cargo.lengthCm().max(cargo.widthCm());
        BigDecimal shortest = cargo.lengthCm().min(cargo.widthCm());
        int longestPalletSide = Math.max(pallet.lengthCm(), pallet.widthCm());
        int shortestPalletSide = Math.min(pallet.lengthCm(), pallet.widthCm());
        if (longest.compareTo(BigDecimal.valueOf(longestPalletSide)) > 0) {
            return prefix + "Kiện có " + (lengthIsLonger ? "chiều dài " : "chiều rộng ") + displayNumber(longest)
                + " cm, vượt " + displayNumber(longest.subtract(BigDecimal.valueOf(longestPalletSide)))
                + " cm so với cạnh dài nhất là " + longestPalletSide + " cm.";
        }
        return prefix + "Cạnh ngắn nhất của kiện là " + displayNumber(shortest)
            + " cm, vượt " + displayNumber(shortest.subtract(BigDecimal.valueOf(shortestPalletSide)))
            + " cm so với cạnh ngắn nhất của pallet là " + shortestPalletSide + " cm.";
    }

    private static String noPalletIssue(RackLayout.Pallet pallet, int itemNumber, Cargo cargo, List<RackSpace> racks) {
        String footprint = footprintIssue(pallet, itemNumber, cargo);
        if (footprint != null) return footprint;
        String label = "Hàng " + itemNumber;
        var tallest = BigDecimal.ZERO;
        var maxCargoWeight = BigDecimal.ZERO;
        boolean hasGeometricPlacement = false;
        for (RackSpace rack : racks) {
            var height = rack.preview.maxCargoHeightCm();
            tallest = tallest.max(height);
            boolean rackHasGeometricPlacement = placements(cargo).stream()
                .anyMatch(placement -> placement.heightCm().compareTo(height) <= 0
                    && grid(pallet, placement.lengthCm(), placement.widthCm()) > 0);
            if (rackHasGeometricPlacement) {
                hasGeometricPlacement = true;
            }
            if (height.compareTo(cargo.heightCm()) >= 0 || rackHasGeometricPlacement) {
                var compartmentWeightLimit = rack.compartments.stream()
                    .map(rack::availableCargoKg)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
                maxCargoWeight = maxCargoWeight.max(compartmentWeightLimit.min(pallet.maxPayloadKg()));
            }
        }
        boolean hasHeightPlacement = racks.stream().anyMatch(rack -> placements(cargo).stream()
            .anyMatch(placement -> placement.heightCm().compareTo(rack.preview.maxCargoHeightCm()) <= 0));
        if (!hasHeightPlacement) {
            if (cargo.allowHorizontalPlacement()) {
                return label + " không có hướng đặt được phép nào vừa chiều cao dành cho hàng là "
                    + displayNumber(tallest) + " cm.";
            }
            return label + " có chiều cao " + displayNumber(cargo.heightCm())
                + " cm, vượt chiều cao dành cho hàng là " + displayNumber(tallest) + " cm.";
        }
        if (!hasGeometricPlacement) {
            return label + " không có hướng đặt được phép nào vừa cả mặt pallet và chiều cao dành cho hàng. ";
        }
        return label + " nặng " + displayNumber(cargo.weightKg()) + " kg mỗi kiện. Trong các khoang đủ chiều cao, "
            + "mỗi pallet chở tối đa " + displayNumber(maxCargoWeight) + " kg hàng.";
    }

    private static String displayNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static final class RackSpace {
        final RackLayout.RackPreview preview;
        final RackLayout.Rack input;
        final List<CompartmentSpace> compartments;
        int freeSlots;
        int usedSlots;
        BigDecimal remainingCargoKg;
        BigDecimal loadedCargoKg = BigDecimal.ZERO;

        RackSpace(RackLayout.RackPreview preview, RackLayout.Rack input, RackLayout.Pallet pallet) {
            this.preview = preview;
            this.input = input;
            BigDecimal cargoPerCompartment = RackLayoutCalculator.cargoLoadForCompartment(
                preview.maxBeamPairLoadKg(), input, pallet);
            compartments = new ArrayList<>();
            for (int index = 0; index < preview.compartmentCount(); index++) {
                compartments.add(new CompartmentSpace(this, index + 1,
                    input.palletsPerCompartment().intValueExact(), cargoPerCompartment));
            }
            freeSlots = preview.palletSlotCount();
            BigDecimal totalCompartmentCargo = cargoPerCompartment
                .multiply(BigDecimal.valueOf(preview.compartmentCount()));
            BigDecimal rackCargoLimit = RackLayoutCalculator.cargoLoadForRack(preview, input, pallet);
            remainingCargoKg = rackCargoLimit.min(totalCompartmentCargo);
        }

        BigDecimal availableCargoKg(CompartmentSpace compartment) {
            return compartment.remainingCargoKg.min(remainingCargoKg).max(BigDecimal.ZERO);
        }

        void load(CompartmentSpace compartment, BigDecimal cargoWeight) {
            compartment.freeSlots--;
            compartment.remainingCargoKg = compartment.remainingCargoKg.subtract(cargoWeight);
            freeSlots--;
            usedSlots++;
            remainingCargoKg = remainingCargoKg.subtract(cargoWeight);
            loadedCargoKg = loadedCargoKg.add(cargoWeight);
        }

        void loadExisting(CompartmentSpace compartment, BigDecimal cargoWeight) {
            compartment.remainingCargoKg = compartment.remainingCargoKg.subtract(cargoWeight);
            remainingCargoKg = remainingCargoKg.subtract(cargoWeight);
            loadedCargoKg = loadedCargoKg.add(cargoWeight);
        }
    }

    private static final class CompartmentSpace {
        final RackSpace rack;
        final int number;
        final BigDecimal cargoCapacityKg;
        int freeSlots;
        BigDecimal remainingCargoKg;

        CompartmentSpace(RackSpace rack, int number, int freeSlots, BigDecimal remainingCargoKg) {
            this.rack = rack;
            this.number = number;
            this.cargoCapacityKg = remainingCargoKg;
            this.freeSlots = freeSlots;
            this.remainingCargoKg = remainingCargoKg;
        }
    }

    private static final class PalletState {
        final int number;
        final RackSpace rack;
        final CompartmentSpace compartment;
        final RackLayout.Pallet pallet;
        final BigDecimal cargoHeightCm;
        final List<PlacedBox> boxes = new ArrayList<>();
        BigDecimal cargoWeightKg = BigDecimal.ZERO;

        PalletState(int number, RackSpace rack, CompartmentSpace compartment,
                    RackLayout.Pallet pallet, BigDecimal cargoHeightCm) {
            this.number = number;
            this.rack = rack;
            this.compartment = compartment;
            this.pallet = pallet;
            this.cargoHeightCm = cargoHeightCm;
        }

        BigDecimal availableCargoKg() {
            BigDecimal palletRemaining = pallet.maxPayloadKg().subtract(cargoWeightKg);
            return palletRemaining.min(compartment.remainingCargoKg).min(rack.remainingCargoKg).max(BigDecimal.ZERO);
        }

        int itemQuantity(int itemNumber) {
            return (int) boxes.stream().filter(box -> box.itemNumber == itemNumber).count();
        }

        int itemLayers(int itemNumber) {
            return boxes.stream().filter(box -> box.itemNumber == itemNumber)
                .mapToInt(box -> box.layer).max().orElse(0);
        }

        void place(Line3d line, int boxNumber, PlacementChoice choice) {
            BigDecimal weight = line.cargo.weightKg();
            if (boxes.isEmpty()) rack.load(compartment, weight);
            else rack.loadExisting(compartment, weight);
            cargoWeightKg = cargoWeightKg.add(weight);
            boxes.add(new PlacedBox(line.itemNumber, boxNumber, choice.layer, choice.x, choice.y, choice.z,
                choice.placement.lengthCm(), choice.placement.widthCm(), choice.placement.heightCm(), line.cargo));
        }
    }
}
