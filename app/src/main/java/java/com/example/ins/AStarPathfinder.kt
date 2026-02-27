package java.com.example.ins

import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

class AStarPathfinder(
    private val mapWidth: Int,
    private val mapHeight: Int,
    private val walkableAreas: List<Rect>,
    private val gridSize: Int
) {

    private val gridWidth = mapWidth / gridSize
    private val gridHeight = mapHeight / gridSize
    private val grid: Array<Array<Node>> = Array(gridWidth) { Array(gridHeight) { Node(0, 0) } }

    private inner class Node(
        val x: Int,
        val y: Int,
        var isWalkable: Boolean = false,
        var parent: Node? = null,
        var gCost: Double = Double.MAX_VALUE,
        var hCost: Double = 0.0
    ) {
        fun fCost(): Double = gCost + hCost
        fun toWorldPoint(): Point = Point(x * gridSize + gridSize / 2, y * gridSize + gridSize / 2)
    }

    init {
        initializeGrid()
    }

    private fun initializeGrid() {
        for (x in 0 until gridWidth) {
            for (y in 0 until gridHeight) {
                grid[x][y] = Node(x, y)
                val cellCenterX = x * gridSize + gridSize / 2
                val cellCenterY = y * gridSize + gridSize / 2

                for (walkableRect in walkableAreas) {
                    if (walkableRect.contains(cellCenterX, cellCenterY)) {
                        grid[x][y].isWalkable = true
                        break
                    }
                }
            }
        }
        Log.d("AStarPathfinder", "Grid initialized based on ${walkableAreas.size} walkable areas.")
    }

    fun findPath(startWorldPoint: Point, endWorldPoint: Point): List<Point>? {
        val startNode = findClosestWalkableNode(startWorldPoint.x, startWorldPoint.y)
        val endNode = findClosestWalkableNode(endWorldPoint.x, endWorldPoint.y)

        if (startNode == null || endNode == null) {
            Log.w("AStarPathfinder", "Could not find a walkable start or end node.")
            return null
        }

        val pathBetweenNodes = calculateAStar(startNode, endNode)

        return if (pathBetweenNodes != null) {
            val finalPath = mutableListOf<Point>()
            finalPath.add(startWorldPoint)
            finalPath.addAll(pathBetweenNodes)
            finalPath.add(endWorldPoint)
            finalPath
        } else {
            null
        }
    }

    private fun calculateAStar(startNode: Node, endNode: Node): List<Point>? {
        if (startNode == endNode) return listOf(startNode.toWorldPoint())

        val openSet = PriorityQueue<Node>(compareBy { it.fCost() })
        val closedSet = hashSetOf<Node>()

        startNode.gCost = 0.0
        startNode.hCost = calculateHeuristic(startNode, endNode)
        openSet.add(startNode)

        while (openSet.isNotEmpty()) {
            val currentNode = openSet.poll() ?: break

            if (currentNode == endNode) {
                return reconstructPath(currentNode)
            }

            closedSet.add(currentNode)

            getNeighbors(currentNode).forEach { neighbor ->
                if (neighbor in closedSet) return@forEach

                val tentativeGCost = currentNode.gCost + calculateDistance(currentNode, neighbor)
                if (tentativeGCost < neighbor.gCost) {
                    neighbor.parent = currentNode
                    neighbor.gCost = tentativeGCost
                    neighbor.hCost = calculateHeuristic(neighbor, endNode)

                    if (neighbor !in openSet) {
                        openSet.add(neighbor)
                    }
                }
            }
        }
        return null
    }

    private fun findClosestWalkableNode(worldX: Int, worldY: Int): Node? {
        val gridX = (worldX / gridSize).coerceIn(0, gridWidth - 1)
        val gridY = (worldY / gridSize).coerceIn(0, gridHeight - 1)

        if (grid[gridX][gridY].isWalkable) {
            return grid[gridX][gridY]
        }

        for (radius in 1 until maxOf(gridWidth, gridHeight)) {
            for (i in -radius..radius) {
                for (j in -radius..radius) {
                    if (abs(i) != radius && abs(j) != radius) continue

                    val checkX = gridX + i
                    val checkY = gridY + j
                    if (checkX in 0 until gridWidth && checkY in 0 until gridHeight) {
                        val node = grid[checkX][checkY]
                        if (node.isWalkable) return node
                    }
                }
            }
        }
        return null
    }

    private fun reconstructPath(endNode: Node): List<Point> {
        val path = mutableListOf<Point>()
        var currentNode: Node? = endNode
        while (currentNode != null) {
            path.add(currentNode.toWorldPoint())
            currentNode = currentNode.parent
        }
        return path.reversed()
    }

    private fun getNeighbors(node: Node): List<Node> {
        val neighbors = mutableListOf<Node>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue

                val newX = node.x + dx
                val newY = node.y + dy

                if (newX in 0 until gridWidth && newY in 0 until gridHeight) {
                    val neighbor = grid[newX][newY]
                    if (neighbor.isWalkable) {
                        if (dx != 0 && dy != 0) { // Diagonal move
                            if (grid[node.x + dx][node.y].isWalkable && grid[node.x][node.y + dy].isWalkable) {
                                neighbors.add(neighbor)
                            }
                        } else { // Straight move
                            neighbors.add(neighbor)
                        }
                    }
                }
            }
        }
        return neighbors
    }

    private fun calculateHeuristic(from: Node, to: Node): Double {
        val dx = abs(from.x - to.x)
        val dy = abs(from.y - to.y)
        return sqrt((dx * dx + dy * dy).toDouble()) // Euclidean distance
    }

    private fun calculateDistance(from: Node, to: Node): Double {
        val dx = abs(from.x - to.x)
        val dy = abs(from.y - to.y)
        return if (dx > 0 && dy > 0) 1.414 else 1.0 // sqrt(2) for diagonal, 1 for straight
    }
}
