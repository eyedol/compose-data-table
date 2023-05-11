/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seanproctor.datatable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Layout model that arranges its children into rows and columns.
 */
@Composable
fun Table(
    columns: Int,
    modifier: Modifier = Modifier,
    alignment: (columnIndex: Int) -> Alignment = { Alignment.CenterStart },
    columnWidth: (columnIndex: Int) -> TableColumnWidth = { TableColumnWidth.Flex(1f) },
    content: TableScope.() -> Unit
) {
    var verticalOffsets by remember { mutableStateOf(emptyArray<Int>()) }
    var horizontalOffsets by remember { mutableStateOf(emptyArray<Int>()) }

    // NOTE(lmr): It is required that we read from verticalOffsets/horizontalOffsets so that the
    // entire Table composable gets recomposed every time they change. This used to work before
    // without us explicitly reading them here because of a compiler bug, but now that the bug is
    // fixed, this is needed. This type of pattern where we are observing the composition of the
    // children of table implicitly and building up a list of composables is a bit error prone
    // and will likely break again in the future when we move to multithreaded composition. I
    // suggest we reevaluate the architecture of this composable.
    @Suppress("UNUSED_EXPRESSION")
    verticalOffsets
    @Suppress("UNUSED_EXPRESSION")
    horizontalOffsets

    val tableChildren: @Composable () -> Unit = with(TableScopeImpl()) {
        apply(content); @Composable {
        val needDecorations = tableDecorationsUnderlay.isNotEmpty() ||
                tableDecorationsOverlay.isNotEmpty()
        val hasOffsets = verticalOffsets.isNotEmpty() && horizontalOffsets.isNotEmpty()
        val decorationsScope = if (needDecorations && hasOffsets) {
            TableDecorationScopeImpl(
                verticalOffsets = verticalOffsets.toList(),
                horizontalOffsets = horizontalOffsets.toList()
            )
        } else {
            null
        }
        if (decorationsScope != null) {
            tableDecorationsUnderlay.forEach { decorationsScope.it() }
        }
        var rowIndex = 0
        @Composable
        fun addRow(height: Dp, textStyle: TextStyle, rowFunction: TableRowScope.() -> Unit) {
            with(TableRowScopeImpl(rowIndex)) {
                rowFunction()
                cells.forEachIndexed { columnIndex, cellFunction ->
                    with(TableCellScopeImpl(rowIndex, columnIndex)) {
                        val cellScope = this
                        Box(
                            Modifier.tableCell()
                                .padding(horizontal = 16.dp)
                                .heightIn(min = height),
                            contentAlignment = alignment(columnIndex)
                        ) {
                            CompositionLocalProvider(
                                LocalTextStyle provides textStyle
                            ) {
                                cellScope.cellFunction()
                            }
                        }
                    }
                }
            }
            rowIndex++
        }
        tableHeader?.let { rowFunction ->
            addRow(56.dp, MaterialTheme.typography.subtitle2, rowFunction)
        }
        tableChildren.forEach { rowFunction ->
            addRow(52.dp, MaterialTheme.typography.body2, rowFunction)
        }
        if (decorationsScope != null) {
            tableDecorationsOverlay.forEach { decorationsScope.it() }
        }
    }
    }
    val layoutDirection = LocalLayoutDirection.current
    Layout(
        tableChildren,
        modifier,
    ) { measurables, constraints ->
        println("Measurables: ${measurables.size}")
        val rowMeasurables = measurables.filter { it.rowIndex != null }.groupBy { it.rowIndex }
        val rows = rowMeasurables.size
        println("Rows: $rows")
        fun measurableAt(row: Int, column: Int) = rowMeasurables[row]?.getOrNull(column)
        val placeables = Array(rows) { arrayOfNulls<Placeable>(columns) }

        // Compute column widths and collect flex information.
        var totalFlex = 0f
        println("Max width: ${constraints.maxWidth}")
        val columnWidths = Array(columns) { 0 }
        var minTableWidth = 0
        var neededColumnWidth = 0
        for (column in 0 until columns) {
            val spec = columnWidth(column)
            val cells = List(rows) { row ->
                TableMeasurable(
                    preferredWidth = {
                        placeables[row][column]?.width
                            ?: measurableAt(row, column)?.measure(Constraints())
                                ?.also { placeables[row][column] = it }?.width ?: 0
                    },
                    minIntrinsicWidth = {
                        measurableAt(row, column)?.minIntrinsicWidth(it) ?: 0
                    },
                    maxIntrinsicWidth = {
                        measurableAt(row, column)?.maxIntrinsicWidth(it) ?: 0
                    }
                )
            }
            minTableWidth += spec.minIntrinsicWidth(cells, constraints.maxWidth, this, constraints.maxHeight)
            columnWidths[column] = spec.preferredWidth(cells, constraints.maxWidth, this)
            neededColumnWidth += columnWidths[column]
            totalFlex += spec.flexValue
        }
        val availableSpace =
            if (constraints.maxWidth == Constraints.Infinity) constraints.maxWidth else max(
                constraints.minWidth,
                minTableWidth
            )
        val remainingSpace = availableSpace - neededColumnWidth

        // Grow flexible columns to fill available horizontal space.
        println("total flex: $totalFlex, available space: $availableSpace")
        if (totalFlex > 0 && remainingSpace > 0) {
            for (column in 0 until columns) {
                val spec = columnWidth(column)
                if (spec.flexValue > 0) {
                    columnWidths[column] += (remainingSpace * (spec.flexValue / totalFlex)).roundToInt()
                }
            }
        }

        // Measure the remaining children and calculate row heights.
        val rowHeights = Array(rows) { 0 }
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (placeables[row][column] == null) {
                    placeables[row][column] = measurableAt(row, column)?.measure(
                        Constraints(minWidth = 0, maxWidth = columnWidths[column])
                    )
                }
                rowHeights[row] =
                    max(rowHeights[row], placeables[row][column]?.height ?: 0)
            }
        }
        println("Column widths: ${columnWidths.toList()}")
        println("Row heights: ${rowHeights.toList()}")

        // Compute row/column offsets.
        val rowOffsets = Array(rows + 1) { 0 }
        val columnOffsets = Array(columns + 1) { 0 }
        for (row in 0 until rows) {
            rowOffsets[row + 1] = rowOffsets[row] + rowHeights[row]
        }
        for (column in 0 until columns) {
            columnOffsets[column + 1] = columnOffsets[column] + columnWidths[column]
        }
        println("Column offsets: ${columnOffsets.toList()}")
        if (!verticalOffsets.contentEquals(rowOffsets)) {
            verticalOffsets = rowOffsets
        }
        if (!horizontalOffsets.contentEquals(columnOffsets)) {
            horizontalOffsets = columnOffsets
        }

        // TODO(calintat): Do something when these do not satisfy constraints.
        val tableSize = constraints.constrain(IntSize(columnOffsets[columns], rowOffsets[rows]))

        layout(tableSize.width, tableSize.height) {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    placeables[row][column]?.let {
                        val position = alignment(column).align(
                            IntSize(it.width, it.height),
                            IntSize(
                                width = columnWidths[column],
                                height = rowHeights[row]
                            ),
                            layoutDirection
                        )
                        println("Placing at: ${columnOffsets[column]} + ${position.x}, ${rowOffsets[row]} + ${position.y}")
                        it.place(
                            x = columnOffsets[column] + position.x,
                            y = rowOffsets[row] + position.y
                        )
                    }
                }
            }
            val decorationConstraints =
                Constraints.fixed(tableSize.width, tableSize.height)
            measurables.filter { it.rowIndex == null }.forEach {
                it.measure(decorationConstraints).place(0, 0)
            }
        }
    }
}
