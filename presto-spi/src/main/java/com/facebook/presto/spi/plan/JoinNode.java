/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.plan;

import com.facebook.presto.spi.SourceLocation;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.concurrent.Immutable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.common.Utils.checkArgument;
import static com.facebook.presto.common.Utils.checkState;
import static com.facebook.presto.spi.plan.JoinDistributionType.PARTITIONED;
import static com.facebook.presto.spi.plan.JoinDistributionType.REPLICATED;
import static com.facebook.presto.spi.plan.JoinType.FULL;
import static com.facebook.presto.spi.plan.JoinType.INNER;
import static com.facebook.presto.spi.plan.JoinType.LEFT;
import static com.facebook.presto.spi.plan.JoinType.RIGHT;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

@Immutable
public final class JoinNode
        extends AbstractJoinNode
{
    private final JoinType type;
    private final PlanNode left;
    private final PlanNode right;
    private final List<EquiJoinClause> criteria;
    private final List<VariableReferenceExpression> outputVariables;
    private final Optional<RowExpression> filter;
    private final Optional<VariableReferenceExpression> leftHashVariable;
    private final Optional<VariableReferenceExpression> rightHashVariable;
    private final Optional<JoinDistributionType> distributionType;
    private final Map<String, VariableReferenceExpression> dynamicFilters;

    @JsonCreator
    public JoinNode(
            Optional<SourceLocation> sourceLocation,
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("type") JoinType type,
            @JsonProperty("left") PlanNode left,
            @JsonProperty("right") PlanNode right,
            @JsonProperty("criteria") List<EquiJoinClause> criteria,
            @JsonProperty("outputVariables") List<VariableReferenceExpression> outputVariables,
            @JsonProperty("filter") Optional<RowExpression> filter,
            @JsonProperty("leftHashVariable") Optional<VariableReferenceExpression> leftHashVariable,
            @JsonProperty("rightHashVariable") Optional<VariableReferenceExpression> rightHashVariable,
            @JsonProperty("distributionType") Optional<JoinDistributionType> distributionType,
            @JsonProperty("dynamicFilters") Map<String, VariableReferenceExpression> dynamicFilters)
    {
        this(sourceLocation, id, Optional.empty(), type, left, right, criteria, outputVariables, filter, leftHashVariable, rightHashVariable, distributionType, dynamicFilters);
    }

    public JoinNode(
            Optional<SourceLocation> sourceLocation,
            PlanNodeId id,
            Optional<PlanNode> statsEquivalentPlanNode,
            JoinType type,
            PlanNode left,
            PlanNode right,
            List<EquiJoinClause> criteria,
            List<VariableReferenceExpression> outputVariables,
            Optional<RowExpression> filter,
            Optional<VariableReferenceExpression> leftHashVariable,
            Optional<VariableReferenceExpression> rightHashVariable,
            Optional<JoinDistributionType> distributionType,
            Map<String, VariableReferenceExpression> dynamicFilters)
    {
        super(sourceLocation, id, statsEquivalentPlanNode);
        requireNonNull(type, "type is null");
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
        requireNonNull(criteria, "criteria is null");
        requireNonNull(outputVariables, "outputVariables is null");
        requireNonNull(filter, "filter is null");
        requireNonNull(leftHashVariable, "leftHashVariable is null");
        requireNonNull(rightHashVariable, "rightHashVariable is null");
        requireNonNull(distributionType, "distributionType is null");
        requireNonNull(dynamicFilters, "dynamicFilters is null");

        this.type = type;
        this.left = left;
        this.right = right;
        this.criteria = unmodifiableList(new ArrayList<>(criteria));
        this.outputVariables = unmodifiableList(new ArrayList<>(outputVariables));
        this.filter = filter;
        this.leftHashVariable = leftHashVariable;
        this.rightHashVariable = rightHashVariable;
        this.distributionType = distributionType;
        this.dynamicFilters = unmodifiableMap(new LinkedHashMap<>(dynamicFilters));

        checkLeftOutputVariablesBeforeRight(left.getOutputVariables(), outputVariables);

        Set<VariableReferenceExpression> inputVariables = new HashSet<>();
        inputVariables.addAll(left.getOutputVariables());
        inputVariables.addAll(right.getOutputVariables());
        checkArgument(inputVariables.containsAll(outputVariables), "Left and right join inputs do not contain all output variables");
        checkArgument(!isCrossJoin() || inputVariables.size() == outputVariables.size(), "Cross join does not support output variables pruning or reordering");

        checkArgument(!(criteria.isEmpty() && leftHashVariable.isPresent()), "Left hash variable is only valid in an equijoin");
        checkArgument(!(criteria.isEmpty() && rightHashVariable.isPresent()), "Right hash variable is only valid in an equijoin");

        if (distributionType.isPresent()) {
            // The implementation of full outer join only works if the data is hash partitioned.
            checkArgument(
                    !(distributionType.get() == REPLICATED && type.mustPartition()),
                    format("%s join do not work with %s distribution type",
                            type,
                            distributionType.get()));
            // It does not make sense to PARTITION when there is nothing to partition on
            checkArgument(
                    !(distributionType.get() == PARTITIONED && type.mustReplicate(criteria)),
                    format("Equi criteria are empty, so %s join should not have %s distribution type",
                            type,
                            distributionType.get()));
        }

        for (VariableReferenceExpression variableReferenceExpression : dynamicFilters.values()) {
            checkArgument(right.getOutputVariables().contains(variableReferenceExpression), format(
                    "Right join input doesn't contain symbol for dynamic filter: %s, rightVariables: %s, dynamicFilters.values(): %s",
                    variableReferenceExpression,
                    Arrays.toString(right.getOutputVariables().toArray()),
                    Arrays.toString(dynamicFilters.values().toArray())));
        }
    }

    public static void checkLeftOutputVariablesBeforeRight(List<VariableReferenceExpression> leftVariables, List<VariableReferenceExpression> outputVariables)
    {
        int leftMaxPosition = -1;
        Optional<Integer> rightMinPosition = Optional.empty();
        Set<VariableReferenceExpression> leftVariablesSet = new HashSet<>(leftVariables);
        for (int i = 0; i < outputVariables.size(); i++) {
            VariableReferenceExpression variable = outputVariables.get(i);
            if (leftVariablesSet.contains(variable)) {
                leftMaxPosition = i;
            }
            else if (!rightMinPosition.isPresent()) {
                rightMinPosition = Optional.of(i);
            }
        }
        checkState(!rightMinPosition.isPresent() || rightMinPosition.get() > leftMaxPosition, "Not all left output variables are before right output variables");
    }

    public JoinNode flipChildren()
    {
        return new JoinNode(
                getSourceLocation(),
                getId(),
                getStatsEquivalentPlanNode(),
                flipType(type),
                right,
                left,
                flipJoinCriteria(criteria),
                flipOutputVariables(getOutputVariables(), left, right),
                filter,
                rightHashVariable,
                leftHashVariable,
                distributionType,
                Collections.emptyMap()); // dynamicFilters are invalid after flipping children
    }

    public static JoinType flipType(JoinType type)
    {
        switch (type) {
            case INNER:
                return INNER;
            case FULL:
                return FULL;
            case LEFT:
                return RIGHT;
            case RIGHT:
                return LEFT;
            default:
                throw new IllegalStateException("No inverse defined for join type: " + type);
        }
    }

    private static List<EquiJoinClause> flipJoinCriteria(List<EquiJoinClause> joinCriteria)
    {
        List<EquiJoinClause> flippedCriteria = joinCriteria.stream()
                .map(EquiJoinClause::flip)
                .collect(Collectors.toList());
        return unmodifiableList(flippedCriteria);
    }

    private static List<VariableReferenceExpression> flipOutputVariables(List<VariableReferenceExpression> outputVariables, PlanNode left, PlanNode right)
    {
        List<VariableReferenceExpression> leftVariables = outputVariables.stream()
                .filter(variable -> left.getOutputVariables().contains(variable))
                .collect(Collectors.toList());
        List<VariableReferenceExpression> rightVariables = outputVariables.stream()
                .filter(variable -> right.getOutputVariables().contains(variable))
                .collect(Collectors.toList());
        List<VariableReferenceExpression> flippedOutput = new ArrayList<VariableReferenceExpression>(rightVariables);
        flippedOutput.addAll(leftVariables);
        return unmodifiableList(flippedOutput);
    }

    @JsonProperty
    public JoinType getType()
    {
        return type;
    }

    @JsonProperty
    public PlanNode getLeft()
    {
        return left;
    }

    @Override
    public PlanNode getProbe()
    {
        return left;
    }

    @JsonProperty
    public PlanNode getRight()
    {
        return right;
    }

    @Override
    public PlanNode getBuild()
    {
        return right;
    }

    @JsonProperty
    public List<EquiJoinClause> getCriteria()
    {
        return criteria;
    }

    @JsonProperty
    public Optional<RowExpression> getFilter()
    {
        return filter;
    }

    @JsonProperty
    public Optional<VariableReferenceExpression> getLeftHashVariable()
    {
        return leftHashVariable;
    }

    @JsonProperty
    public Optional<VariableReferenceExpression> getRightHashVariable()
    {
        return rightHashVariable;
    }

    @Override
    public List<PlanNode> getSources()
    {
        List<PlanNode> sources = new ArrayList<>();
        sources.add(left);
        sources.add(right);
        return unmodifiableList(sources);
    }

    @Override
    public LogicalProperties computeLogicalProperties(LogicalPropertiesProvider logicalPropertiesProvider)
    {
        requireNonNull(logicalPropertiesProvider, "logicalPropertiesProvider cannot be null.");
        return logicalPropertiesProvider.getJoinProperties(this);
    }

    @Override
    @JsonProperty
    public List<VariableReferenceExpression> getOutputVariables()
    {
        return outputVariables;
    }

    @JsonProperty
    public Optional<JoinDistributionType> getDistributionType()
    {
        return distributionType;
    }

    @Override
    @JsonProperty
    public Map<String, VariableReferenceExpression> getDynamicFilters()
    {
        return dynamicFilters;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitJoin(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        checkArgument(newChildren.size() == 2, "expected newChildren to contain 2 nodes");
        return new JoinNode(getSourceLocation(), getId(), getStatsEquivalentPlanNode(), type, newChildren.get(0), newChildren.get(1), criteria, outputVariables, filter, leftHashVariable, rightHashVariable, distributionType, dynamicFilters);
    }

    @Override
    public PlanNode assignStatsEquivalentPlanNode(Optional<PlanNode> statsEquivalentPlanNode)
    {
        return new JoinNode(getSourceLocation(), getId(), statsEquivalentPlanNode, type, left, right, criteria, outputVariables, filter, leftHashVariable, rightHashVariable, distributionType, dynamicFilters);
    }

    public JoinNode withDistributionType(JoinDistributionType distributionType)
    {
        return new JoinNode(getSourceLocation(), getId(), getStatsEquivalentPlanNode(), type, left, right, criteria, outputVariables, filter, leftHashVariable, rightHashVariable, Optional.of(distributionType), dynamicFilters);
    }

    public boolean isCrossJoin()
    {
        return criteria.isEmpty() && !filter.isPresent() && type == INNER;
    }
}
