/*
 *  Copyright 2019 wjybxx
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to iBn writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wjybxx.zset.obj2long;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.*;

/**
 * key为泛型，score为long类型的sorted set - 参考redis的zset实现
 * <b>排序规则</b>
 * 有序集合里面的成员是不能重复的，都是唯一的，但是，不同成员间有可能有相同的分数。
 * 当多个成员有相同的分数时，它们将按照键排序。
 * 即：分数作为第一排序条件，键作为第二排序条件，当分数相同时，比较键的大小。
 * <p>
 * <b>NOTE</b>：
 * 1. ZSET中的排名从0开始（提供给用户的接口，排名都从0开始）
 * 2. ZSET使用键的<b>compare</b>结果判断两个键是否相等，而不是equals方法，因此必须保证键不同时compare结果一定不为0。
 * 3. 又由于key需要存放于{@link HashMap}中，因此“相同”的key必须有相同的hashCode，且equals方法返回true。
 * <b>手动加粗:key的关键属性最好是number或string</b>
 * <p>
 * 4. 我们允许zset中的成员是降序排列的(ScoreComparator决定)，可以更好的支持根据score降序的排行榜，
 * 而不是强迫你总是调用反转系列接口{@code zrev...}，那样的设计不符合人的正常思维，就很容易出错。
 * <p>
 * 5. 我们修改了redis中根据min和max查找和删除成员的接口，修改为start和end，当根据score范围查找或删除元素时，并不要求start小于等于end，我们会处理它们的大小关系。<br>
 * Q: 为什么要这么改动呢？<br>
 * A: 举个栗子：假如ScoreComparator比较两个long类型的score是逆序的，现在要删除排行榜中 1-10000分的成员，如果方法告诉你要传入的的是min和max，
 * 你会很自然的传入想到 (1,10000) 而不是 (10000,1)。因此，如果接口不做调整，这个接口就太反人类了，谁用都得错。
 *
 * <p>
 * 这里只实现了redis zset中的几个常用的接口，扩展不是太麻烦，可以自己根据需要实现。
 *
 * @param <K> the type of key
 * @author wjybxx
 * @version 1.0
 * date - 2019/11/4
 */
@NotThreadSafe
public class Obj2LongZSet<K> implements Iterable<Obj2LongMember<K>> {

    /**
     * obj -> score
     */
    private final Map<K, Long> dict = new HashMap<>(128);
    /**
     * scoreList
     */
    private final SkipList<K> zsl;

    private Obj2LongZSet(Comparator<K> keyComparator, LongScoreHandler scoreHandler) {
        this.zsl = new SkipList<>(keyComparator, scoreHandler);
    }

    /**
     * 创建一个键为string类型的zset
     *
     * @param scoreHandler score比较器，默认实现见{@link LongScoreHandlers}
     * @return zset
     */
    public static Obj2LongZSet<String> newStringKeyZSet(LongScoreHandler scoreHandler) {
        return new Obj2LongZSet<>(String::compareTo, scoreHandler);
    }

    /**
     * 创建一个键为long类型的zset
     *
     * @param scoreHandler score比较器，默认实现见{@link LongScoreHandlers}
     * @return zset
     */
    public static Obj2LongZSet<Long> newLongKeyZSet(LongScoreHandler scoreHandler) {
        return new Obj2LongZSet<>(Long::compareTo, scoreHandler);
    }

    /**
     * 创建一个键为int类型的zset
     *
     * @param scoreHandler score比较器，默认实现见{@link LongScoreHandlers}
     * @return zset
     */
    public static Obj2LongZSet<Integer> newIntKeyZSet(LongScoreHandler scoreHandler) {
        return new Obj2LongZSet<>(Integer::compareTo, scoreHandler);
    }

    /**
     * 创建一个自定义键类型的zset
     *
     * @param keyComparator 键比较器，当score比较结果相等时，比较key。
     *                      <b>请仔细阅读类文档中的注意事项</b>。
     * @param scoreHandler  score比较器，默认实现见{@link LongScoreHandlers}
     * @param <K>           键的类型
     * @return zset
     */
    public static <K> Obj2LongZSet<K> newGenericKeyZSet(Comparator<K> keyComparator, LongScoreHandler scoreHandler) {
        return new Obj2LongZSet<>(keyComparator, scoreHandler);
    }
    // -------------------------------------------------------- insert -----------------------------------------------

    /**
     * 往有序集合中新增一个成员。
     * 如果指定添加的成员已经是有序集合里面的成员，则会更新成员的分数（score）并更新到正确的排序位置。
     *
     * @param score  数据的评分
     * @param member 成员id
     */
    public void zadd(final long score, @Nonnull final K member) {
        final Long oldScore = dict.put(member, score);
        if (oldScore != null) {
            if (!zsl.scoreEquals(oldScore, score)) {
                zsl.zslDelete(oldScore, member);
                zsl.zslInsert(score, member);
            }
        } else {
            zsl.zslInsert(score, member);
        }
    }

    /**
     * 往有序集合中新增一个成员。当且仅当该成员不在有序集合时才添加。
     *
     * @param score  数据的评分
     * @param member 成员id
     * @return 添加成功则返回true，否则返回false。
     */
    public boolean zaddnx(final long score, @Nonnull final K member) {
        final Long oldScore = dict.putIfAbsent(member, score);
        if (oldScore == null) {
            zsl.zslInsert(score, member);
            return true;
        }
        return false;
    }

    /**
     * 为有序集的成员member的score值加上增量increment，并更新到正确的排序位置。
     * 如果有序集中不存在member，就在有序集中添加一个member，score是increment（就好像它之前的score是0）
     *
     * @param increment 自定义增量
     * @param member    成员id
     * @return 新值
     */
    public long zincrby(long increment, @Nonnull K member) {
        final Long oldScore = dict.get(member);
        final long score = oldScore == null ? increment : zsl.sum(oldScore, increment);
        zadd(score, member);
        return score;
    }

    // -------------------------------------------------------- remove -----------------------------------------------

    /**
     * 删除指定成员
     *
     * @param member 成员id
     * @return 如果成员存在，则返回对应的score，否则返回null。
     */
    public Long zrem(@Nonnull K member) {
        final Long oldScore = dict.remove(member);
        if (oldScore != null) {
            zsl.zslDelete(oldScore, member);
            return oldScore;
        } else {
            return null;
        }
    }

    // region 通过score删除成员

    /**
     * 移除zset中所有score值介于start和end之间(包括等于start或end)的成员
     *
     * @param start 起始分数 inclusive
     * @param end   截止分数 inclusive
     * @return 删除的成员数目
     */
    public int zremrangeByScore(long start, long end) {
        return zremrangeByScore(zsl.newRangeSpec(start, end));
    }

    /**
     * 移除zset中所有score值在范围区间的成员
     *
     * @param spec score范围区间
     * @return 删除的成员数目
     */
    private int zremrangeByScore(@Nonnull LongScoreRangeSpec spec) {
        return zremrangeByScore(zsl.newRangeSpec(spec));
    }

    /**
     * 移除zset中所有score值在范围区间的成员
     *
     * @param spec score范围区间
     * @return 删除的成员数目
     */
    private int zremrangeByScore(@Nonnull ZLongScoreRangeSpec spec) {
        return zsl.zslDeleteRangeByScore(spec, dict);
    }

    // endregion

    // region 通过排名删除成员

    /**
     * 删除指定排名的成员
     *
     * @param rank 排名 0-based
     * @return 删除成功则返回该排名对应的数据，否则返回null
     */
    public Obj2LongMember<K> zremByRank(int rank) {
        if (rank < 0 || rank >= zsl.length()) {
            return null;
        }
        final SkipListNode<K> delete = zsl.zslDeleteByRank(rank + 1, dict);
        assert null != delete;
        return new Obj2LongMember<>(delete.obj, delete.score);
    }


    /**
     * 删除并返回有序集合中的第一个成员。
     * - 不使用min和max，是因为score的比较方式是用户自定义的。
     *
     * @return 如果不存在，则返回null
     */
    @Nullable
    public Obj2LongMember<K> zpopFisrt() {
        return zremByRank(0);
    }

    /**
     * 删除并返回有序集合中的最后一个成员。
     * - 不使用min和max，是因为score的比较方式是用户自定义的。
     *
     * @return 如果不存在，则返回null
     */
    @Nullable
    public Obj2LongMember<K> zpopLast() {
        return zremByRank(zsl.length() - 1);
    }

    /**
     * 删除指定排名范围的全部成员，start和end都是从0开始的。
     * 排名0表示分数最小的成员。
     * start和end都可以是负数，此时它们表示从最高排名成员开始的偏移量，eg: -1表示最高排名的成员， -2表示第二高分的成员，以此类推。
     * <p>
     * <b>Time complexity:</b> O(log(N))+O(M) with N being the number of elements in the sorted set
     * and M the number of elements removed by the operation
     *
     * @param start 起始排名
     * @param end   截止排名
     * @return 删除的成员数目
     */
    public int zremrangeByRank(int start, int end) {
        final int zslLength = zsl.length();

        start = convertStartRank(start, zslLength);
        end = convertEndRank(end, zslLength);

        if (isRankRangeEmpty(start, end, zslLength)) {
            return 0;
        }

        return zsl.zslDeleteRangeByRank(start + 1, end + 1, dict);
    }

    /**
     * 转换起始排名
     *
     * @param start     请求参数中的起始排名，0-based
     * @param zslLength 跳表的长度
     * @return 有效起始排名
     */
    private static int convertStartRank(int start, int zslLength) {
        if (start < 0) {
            start += zslLength;
        }
        if (start < 0) {
            start = 0;
        }
        return start;
    }

    /**
     * 转换截止排名
     *
     * @param end       请求参数中的截止排名，0-based
     * @param zslLength 跳表的长度
     * @return 有效截止排名
     */
    private static int convertEndRank(int end, int zslLength) {
        if (end < 0) {
            end += zslLength;
        }
        if (end >= zslLength) {
            end = zslLength - 1;
        }
        return end;
    }

    /**
     * 判断排名区间是否为空
     *
     * @param start     转换后的起始排名
     * @param end       转换后的截止排名
     * @param zslLength 跳表长度
     * @return true/false
     */
    private static boolean isRankRangeEmpty(final int start, final int end, final int zslLength) {
        /* Invariant: start >= 0, so this test will be true when end < 0.
         * The range is empty when start > end or start >= length. */
        return start > end || start >= zslLength;
    }
    // endregion

    // region 限制成员数量

    /**
     * 删除zset中尾部多余的成员，将zset中的成员数量限制到count之内。
     * 保留前面的count个数成员
     *
     * @param count 剩余数量限制
     * @return 删除的成员数量
     */
    public int zlimit(int count) {
        if (zsl.length() <= count) {
            return 0;
        }
        return zsl.zslDeleteRangeByRank(count + 1, zsl.length(), dict);
    }

    /**
     * 删除zset中头部多余的成员，将zset中的成员数量限制到count之内。
     * - 保留后面的count个数成员
     *
     * @param count 剩余数量限制
     * @return 删除的成员数量
     */
    public int zrevlimit(int count) {
        if (zsl.length() <= count) {
            return 0;
        }
        return zsl.zslDeleteRangeByRank(1, zsl.length() - count, dict);
    }
    // endregion

    // -------------------------------------------------------- query -----------------------------------------------

    /**
     * 返回有序集成员member的score值。
     * 如果member成员不是有序集的成员，返回null - 这里返回任意的基础值都是不合理的，因此必须返回null。
     *
     * @param member 成员id
     * @return score
     */
    public Long zscore(@Nonnull K member) {
        return dict.get(member);
    }

    /**
     * 返回有序集中成员member的排名。其中有序集成员按score值递增(从小到大)顺序排列。
     * 返回的排名从0开始(0-based)，也就是说，score值最小的成员排名为0。
     * 使用{@link #zrevrank(Object)}可以获得成员按score值递减(从大到小)排列的排名。
     * <p>
     * <b>Time complexity:</b> O(log(N))
     * <p>
     * <b>与redis的区别</b>：我们使用-1表示成员不存在，而不是返回null。
     *
     * @param member 成员id
     * @return 如果存在该成员，则返回该成员的排名，否则返回-1
     */
    public int zrank(@Nonnull K member) {
        final Long score = dict.get(member);
        if (score == null) {
            return -1;
        }
        // 0 < zslGetRank <= size
        return zsl.zslGetRank(score, member) - 1;
    }

    /**
     * 返回有序集中成员member的排名，其中有序集成员按score值从大到小排列。
     * 返回的排名从0开始(0-based)，也就是说，score值最大的成员排名为0。
     * 使用{@link #zrank(Object)}可以获得成员按score值递增(从小到大)排列的排名。
     * <p>
     * <b>Time complexity:</b> O(log(N))
     * <p>
     * <b>与redis的区别</b>：我们使用-1表示成员不存在，而不是返回null。
     *
     * @param member 成员id
     * @return 如果存在该成员，则返回该成员的排名，否则返回-1
     */
    public int zrevrank(@Nonnull K member) {
        final Long score = dict.get(member);
        if (score == null) {
            return -1;
        }
        // 0 < zslGetRank <= size
        return zsl.length() - zsl.zslGetRank(score, member);
    }

    /**
     * 获取指定排名的成员数据。
     * 成员被认为是从低分到高分排序的。
     * 具有相同分数的成员按字典序排列。
     *
     * @param rank 排名 0-based
     * @return memver，如果不存在，则返回null
     */
    public Obj2LongMember<K> zmemberByRank(int rank) {
        if (rank < 0 || rank >= zsl.length()) {
            return null;
        }
        final SkipListNode<K> node = zsl.zslGetElementByRank(rank + 1);
        assert null != node;
        return new Obj2LongMember<>(node.obj, node.score);
    }

    /**
     * 获取指定逆序排名的成员数据。
     * 成员被认为是从高分到低分排序的。
     * 具有相同score值的成员按字典序的反序排列。
     *
     * @param rank 排名 0-based
     * @return memver，如果不存在，则返回null
     */
    public Obj2LongMember<K> zrevmemberByRank(int rank) {
        if (rank < 0 || rank >= zsl.length()) {
            return null;
        }
        final SkipListNode<K> node = zsl.zslGetElementByRank(zsl.length() - rank);
        assert null != node;
        return new Obj2LongMember<>(node.obj, node.score);
    }

    // region 通过分数查询

    /**
     * 返回有序集合中的分数在start和end之间的所有成员（包括分数等于start或者end的成员）。
     * 成员被认为是从低分到高分排序的。
     * 具有相同分数的成员按字典序排列。
     *
     * @param start 起始分数 inclusive
     * @param end   截止分数 inclusive
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrangeByScore(long start, long end) {
        return zrangeByScoreWithOptions(zsl.newRangeSpec(start, end), 0, -1, false);
    }

    /**
     * 返回有序集合中的分数在指定范围区间的所有成员。
     * 成员被认为是从低分到高分排序的。
     * 具有相同分数的成员按字典序排列。
     *
     * @param spec 范围描述信息
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrangeByScore(LongScoreRangeSpec spec) {
        return zrangeByScoreWithOptions(zsl.newRangeSpec(spec), 0, -1, false);
    }

    /**
     * 返回有序集合中的分数在start和end之间的所有成员（包括分数等于start或者end的成员）。
     * 成员被认为是从高分到低分排序的。
     * 具有相同score值的成员按字典序的反序排列。
     *
     * @param start 起始分数 inclusive
     * @param end   截止分数 inclusive
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrevrangeByScore(final long start, final long end) {
        return zrangeByScoreWithOptions(zsl.newRangeSpec(start, end), 0, -1, true);
    }

    /**
     * 返回有序集合中的分数在指定范围之间的所有成员。
     * 成员被认为是从高分到低分排序的。
     * 具有相同score值的成员按字典序的反序排列。     *
     *
     * @param rangeSpec score范围区间
     * @return 删除的成员数目
     */
    public List<Obj2LongMember<K>> zrevrangeByScore(LongScoreRangeSpec rangeSpec) {
        return zrangeByScoreWithOptions(zsl.newRangeSpec(rangeSpec), 0, -1, true);
    }

    /**
     * 返回zset中指定分数区间内的成员，并按照指定顺序返回
     *
     * @param rangeSpec score范围描述信息
     * @param offset    偏移量(用于分页)  大于等于0
     * @param limit     返回的成员数量(用于分页) 小于0表示不限制
     * @param reverse   是否逆序
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrangeByScoreWithOptions(final LongScoreRangeSpec rangeSpec, int offset, int limit, boolean reverse) {
        return zrangeByScoreWithOptions(zsl.newRangeSpec(rangeSpec), offset, limit, reverse);
    }

    /**
     * 返回zset中指定分数区间内的成员，并按照指定顺序返回
     *
     * @param range   score范围描述信息
     * @param offset  偏移量(用于分页)  大于等于0
     * @param limit   返回的成员数量(用于分页) 小于0表示不限制
     * @param reverse 是否逆序
     * @return memberInfo
     */
    private List<Obj2LongMember<K>> zrangeByScoreWithOptions(final ZLongScoreRangeSpec range, int offset, int limit, boolean reverse) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset" + ": " + offset + " (expected: >= 0)");
        }

        SkipListNode<K> listNode;
        /* If reversed, get the last node in range as starting point. */
        if (reverse) {
            listNode = zsl.zslLastInRange(range);
        } else {
            listNode = zsl.zslFirstInRange(range);
        }

        /* No "first" element in the specified interval. */
        if (listNode == null) {
            return new ArrayList<>();
        }

        /* If there is an offset, just traverse the number of elements without
         * checking the score because that is done in the next loop. */
        while (listNode != null && offset-- != 0) {
            if (reverse) {
                listNode = listNode.backward;
            } else {
                listNode = listNode.levelInfo[0].forward;
            }
        }

        final List<Obj2LongMember<K>> result = new ArrayList<>();

        /* 这里使用 != 0 判断，当limit小于0时，表示不限制 */
        while (listNode != null && limit-- != 0) {
            /* Abort when the node is no longer in range. */
            if (reverse) {
                if (!zsl.zslValueGteMin(listNode.score, range)) {
                    break;
                }
            } else {
                if (!zsl.zslValueLteMax(listNode.score, range)) {
                    break;
                }
            }

            result.add(new Obj2LongMember<>(listNode.obj, listNode.score));

            /* Move to next node */
            if (reverse) {
                listNode = listNode.backward;
            } else {
                listNode = listNode.levelInfo[0].forward;
            }
        }
        return result;
    }
    // endregion

    // region 通过排名查询

    /**
     * 查询指定排名区间的成员id和分数，结果排名由低到高。
     * start和end都是从0开始的。
     * 其中成员的位置按score值递增(从小到大)来排列，排名0表示分数最小的成员。
     * start和end都可以是负数，此时它们表示从最高排名成员开始的偏移量，eg: -1表示最高排名的成员， -2表示第二高分的成员，以此类推。
     *
     * @param start 起始排名 inclusive
     * @param end   截止排名 inclusive
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrangeByRank(int start, int end) {
        return zrangeByRankInternal(start, end, false);
    }

    /**
     * 返回有序集中，指定区间内的成员。
     * start和end都是从0开始的。
     * 其中成员的位置按score值递减(从大到小)来排列，排名0表示分数最高的成员。
     * 具有相同score值的成员按字典序的反序排列。
     * 除了成员按score值递减的次序排列这一点外，{@code zrevrangeByRank} 方法的其它方面和 {@link #zrangeByRank(int, int)}相同。
     *
     * @param start 起始排名 inclusive
     * @param end   截止排名 inclusive
     * @return memberInfo
     */
    public List<Obj2LongMember<K>> zrevrangeByRank(int start, int end) {
        return zrangeByRankInternal(start, end, true);
    }

    /**
     * 查询指定排名区间的成员id和分数，start和end都是从0开始的。
     *
     * @param start   起始排名 inclusive
     * @param end     截止排名 inclusive
     * @param reverse 是否逆序返回
     * @return 区间范围内的成员id和score
     */
    private List<Obj2LongMember<K>> zrangeByRankInternal(int start, int end, boolean reverse) {
        final int zslLength = zsl.length();

        start = convertStartRank(start, zslLength);
        end = convertEndRank(end, zslLength);

        if (isRankRangeEmpty(start, end, zslLength)) {
            return new ArrayList<>();
        }

        int rangeLen = end - start + 1;
        SkipListNode<K> listNode;

        /* start >= 0，大于0表示需要进行调整 */
        /* Check if starting point is trivial, before doing log(N) lookup. */
        if (reverse) {
            listNode = start > 0 ? zsl.zslGetElementByRank(zslLength - start) : zsl.tail;
        } else {
            listNode = start > 0 ? zsl.zslGetElementByRank(start + 1) : zsl.header.levelInfo[0].forward;
        }

        final List<Obj2LongMember<K>> result = new ArrayList<>(rangeLen);
        while (rangeLen-- > 0 && listNode != null) {
            result.add(new Obj2LongMember<>(listNode.obj, listNode.score));
            listNode = reverse ? listNode.backward : listNode.levelInfo[0].forward;
        }
        return result;
    }
    // endregion

    // region 统计分数人数

    /**
     * 返回有序集key中，score值在指定区间(包括score值等于start或end)的成员
     *
     * @param start 起始分数
     * @param end   截止分数
     * @return 分数区间段内的成员数量
     */
    public int zcount(long start, long end) {
        return zcountInternal(zsl.newRangeSpec(start, end));
    }

    /**
     * 返回有序集key中，score值在指定区间的成员
     *
     * @param rangeSpec score区间描述信息
     * @return 分数区间段内的成员数量
     */
    public int zcount(LongScoreRangeSpec rangeSpec) {
        return zcountInternal(zsl.newRangeSpec(rangeSpec));
    }

    /**
     * 返回有序集key中，score值在指定区间的成员
     *
     * @param range score区间描述信息
     * @return 分数区间段内的成员数量
     */
    private int zcountInternal(final ZLongScoreRangeSpec range) {
        int count = 0;
        final SkipListNode<K> firstNodeInRange = zsl.zslFirstInRange(range);

        if (firstNodeInRange != null) {
            final int firstNodeRank = zsl.zslGetRank(firstNodeInRange.score, firstNodeInRange.obj);

            /* 如果firstNodeInRange不为null，那么lastNode也一定不为null(最坏的情况下firstNode就是lastNode) */
            final SkipListNode<K> lastNodeInRange = zsl.zslLastInRange(range);
            assert lastNodeInRange != null;
            final int lastNodeRank = zsl.zslGetRank(lastNodeInRange.score, lastNodeInRange.obj);

            return lastNodeRank - firstNodeRank + 1;
        }
        return count;
    }

    /**
     * @return zset中的成员数量
     */
    public int zcard() {
        return zsl.length();
    }

    // endregion

    // region 迭代

    /**
     * 迭代有序集中的所有元素
     *
     * @return iterator
     */
    @Nonnull
    public Iterator<Obj2LongMember<K>> zscan() {
        return zscan(0);
    }

    /**
     * 从指定偏移量开始迭代有序集中的元素
     *
     * @param offset 偏移量，如果小于等于0，则等价于{@link #zscan()}
     * @return iterator
     */
    @Nonnull
    public Iterator<Obj2LongMember<K>> zscan(int offset) {
        if (offset <= 0) {
            return new ZSetItr(zsl.header.directForward());
        }

        if (offset >= zsl.length()) {
            return new ZSetItr(null);
        }

        return new ZSetItr(zsl.zslGetElementByRank(offset + 1));
    }

    @Nonnull
    @Override
    public Iterator<Obj2LongMember<K>> iterator() {
        return zscan(0);
    }
    // endregion

    /**
     * @return zset中当前的成员信息，用于测试
     */
    public String dump() {
        return zsl.dump();
    }

    // ------------------------------------------------------- 内部实现 ----------------------------------------

    /**
     * 跳表
     * 注意：跳表的排名是从1开始的。
     *
     * @author wjybxx
     * @version 1.0
     * date - 2019/11/4
     */
    private static class SkipList<K> {

        /**
         * 跳表允许最大层级
         */
        private static final int ZSKIPLIST_MAXLEVEL = 32;

        /**
         * 跳表升层概率
         */
        private static final float ZSKIPLIST_P = 0.25f;

        /**
         * {@link Random}本身是线程安全的，但是多线程使用会产生不必要的竞争，因此创建一个独立的random对象。
         * - 其实也可以使用{@link java.util.concurrent.ThreadLocalRandom}
         */
        private final Random random = new Random();
        /**
         * 更新节点使用的缓存 - 避免频繁的申请空间
         */
        @SuppressWarnings("unchecked")
        private final SkipListNode<K>[] updateCache = new SkipListNode[ZSKIPLIST_MAXLEVEL];
        /**
         * 插入节点的排名缓存
         */
        private final int[] rankCache = new int[ZSKIPLIST_MAXLEVEL];

        private final Comparator<K> objComparator;
        private final LongScoreHandler scoreHandler;
        /**
         * 修改次数 - 防止错误的迭代
         */
        private int modCount = 0;

        /**
         * 跳表头结点 - 哨兵
         * 1. 可以简化判定逻辑
         * 2. 恰好可以使得rank从1开始
         */
        private final SkipListNode<K> header;

        /**
         * 跳表尾节点
         */
        private SkipListNode<K> tail;

        /**
         * 跳表成员个数
         * 注意：head头指针不包含在length计数中。
         */
        private int length = 0;

        /**
         * level表示SkipList的总层数，即所有节点层数的最大值。
         */
        private int level = 1;

        SkipList(Comparator<K> objComparator, LongScoreHandler scoreHandler) {
            this.objComparator = objComparator;
            this.scoreHandler = scoreHandler;
            this.header = zslCreateNode(ZSKIPLIST_MAXLEVEL, 0, null);
        }

        /**
         * 插入一个新的节点到跳表。
         * 这里假定成员已经不存在（直到调用方执行该方法）。
         * <p>
         * zslInsert a new node in the skiplist. Assumes the element does not already
         * exist (up to the caller to enforce that).
         * <pre>
         *             header                    newNode
         *               _                                                 _
         * level - 1    |_| pre                                           |_|
         *  |           |_| pre                    _                      |_|
         *  |           |_| pre  _                |_|                     |_|
         *  |           |_|  ↓  |_| pre  _        |_|      _              |_|
         *  |           |_|     |_|  ↓  |_| pre   |_|     |_|             |_|
         *  |           |_|     |_|     |_| pre   |_|     |_|      _      |_|
         *  |           |_|     |_|     |_| pre   |_|     |_|     |_|     |_|
         *  0           |0|     |1|     |2| pre   |_|     |3|     |4|     |5|
         * </pre>
         *
         * @param score 分数
         * @param obj   obj 分数对应的成员id
         */
        @SuppressWarnings("UnusedReturnValue")
        SkipListNode zslInsert(long score, K obj) {
            // 新节点的level
            final int level = zslRandomLevel();

            // update - 需要更新后继节点的Node，新节点各层的前驱节点
            // 1. 分数小的节点
            // 2. 分数相同但id小的节点（分数相同时根据数据排序）
            // rank - 新节点各层前驱的当前排名
            // 这里不必创建一个ZSKIPLIST_MAXLEVEL长度的数组，它取决于插入节点后的新高度，你在别处看见的代码会造成大量的空间浪费，增加GC压力。
            // 如果创建的都是ZSKIPLIST_MAXLEVEL长度的数组，那么应该实现缓存
//            @SuppressWarnings("unchecked")
//            final SkipListNode<K>[] update = new SkipListNode[Math.max(level, this.level)];
//            final int[] rank = new int[update.length];

            final SkipListNode<K>[] update = updateCache;
            final int[] rank = rankCache;

            try {
                // preNode - 新插入节点的前驱节点
                SkipListNode<K> preNode = header;
                for (int i = this.level - 1; i >= 0; i--) {
                    /* store rank that is crossed to reach the insert position */
                    if (i == (this.level - 1)) {
                        // 起始点，也就是head，它的排名就是0
                        rank[i] = 0;
                    } else {
                        // 由于是回溯降级继续遍历，因此其初始排名是前一次遍历的排名
                        rank[i] = rank[i + 1];
                    }

                    while (preNode.levelInfo[i].forward != null &&
                            compareScoreAndObj(preNode.levelInfo[i].forward, score, obj) < 0) {
                        // preNode的后继节点仍然小于要插入的节点，需要继续前进，同时累计排名
                        rank[i] += preNode.levelInfo[i].span;
                        preNode = preNode.levelInfo[i].forward;
                    }

                    // 这是要插入节点的第i层的前驱节点，此时触发降级
                    update[i] = preNode;
                }

                if (level > this.level) {
                    /* 新节点的层级大于当前层级，那么高出来的层级导致需要更新head，且排名和跨度是固定的 */
                    for (int i = this.level; i < level; i++) {
                        rank[i] = 0;
                        update[i] = this.header;
                        update[i].levelInfo[i].span = this.length;
                    }
                    this.level = level;
                }

                /* 由于我们允许的重复score，并且zslInsert(该方法)的调用者在插入前必须测试要插入的member是否已经在hash表中。
                 * 因此我们假设key（obj）尚未被插入，并且重复插入score的情况永远不会发生。*/
                /* we assume the key is not already inside, since we allow duplicated
                 * scores, and the re-insertion of score and redis object should never
                 * happen since the caller of zslInsert() should test in the hash table
                 * if the element is already inside or not.*/
                final SkipListNode<K> newNode = zslCreateNode(level, score, obj);

                /* 这些节点的高度小于等于新插入的节点的高度，需要更新指针。此外它们当前的跨度被拆分了两部分，需要重新计算。 */
                for (int i = 0; i < level; i++) {
                    /* 链接新插入的节点 */
                    newNode.levelInfo[i].forward = update[i].levelInfo[i].forward;
                    update[i].levelInfo[i].forward = newNode;

                    /* rank[0] 是新节点的直接前驱的排名，每一层都有一个前驱，可以通过彼此的排名计算跨度 */
                    /* 计算新插入节点的跨度 和 重新计算所有前驱节点的跨度，之前的跨度被拆分为了两份*/
                    /* update span covered by update[i] as newNode is inserted here */
                    newNode.levelInfo[i].span = update[i].levelInfo[i].span - (rank[0] - rank[i]);
                    update[i].levelInfo[i].span = (rank[0] - rank[i]) + 1;
                }

                /*  这些节点高于新插入的节点，它们的跨度可以简单的+1 */
                /* increment span for untouched levels */
                for (int i = level; i < this.level; i++) {
                    update[i].levelInfo[i].span++;
                }

                /* 设置新节点的前向节点(回溯节点) - 这里不包含header，一定注意 */
                newNode.backward = (update[0] == this.header) ? null : update[0];

                /* 设置新节点的后向节点 */
                if (newNode.levelInfo[0].forward != null) {
                    newNode.levelInfo[0].forward.backward = newNode;
                } else {
                    this.tail = newNode;
                }

                this.length++;
                this.modCount++;

                return newNode;
            } finally {
                releaseUpdate(update);
                releaseRank(rank);
            }
        }

        /**
         * Delete an element with matching score/object from the skiplist.
         *
         * @param score 分数用于快速定位节点
         * @param obj   用于确定节点是否是对应的数据节点
         */
        @SuppressWarnings("UnusedReturnValue")
        boolean zslDelete(long score, K obj) {
            // update - 需要更新后继节点的Node
            // 1. 分数小的节点
            // 2. 分数相同但id小的节点（分数相同时根据数据排序）
//            final SkipListNode[] update = new SkipListNode[this.level];
            final SkipListNode<K>[] update = updateCache;
            try {
                SkipListNode<K> preNode = this.header;
                for (int i = this.level - 1; i >= 0; i--) {
                    while (preNode.levelInfo[i].forward != null &&
                            compareScoreAndObj(preNode.levelInfo[i].forward, score, obj) < 0) {
                        // preNode的后继节点仍然小于要删除的节点，需要继续前进
                        preNode = preNode.levelInfo[i].forward;
                    }
                    // 这是目标节点第i层的可能前驱节点
                    update[i] = preNode;
                }

                /* 由于可能多个节点拥有相同的分数，因此必须同时比较score和object */
                /* We may have multiple elements with the same score, what we need
                 * is to find the element with both the right score and object. */
                final SkipListNode<K> targetNode = preNode.levelInfo[0].forward;
                if (targetNode != null && scoreEquals(targetNode.score, score) && objEquals(targetNode.obj, obj)) {
                    zslDeleteNode(targetNode, update);
                    return true;
                }

                /* not found */
                return false;
            } finally {
                releaseUpdate(update);
            }
        }

        /**
         * Internal function used by zslDelete, zslDeleteByScore and zslDeleteByRank
         *
         * @param deleteNode 要删除的节点
         * @param update     可能要更新的节点们
         */
        private void zslDeleteNode(final SkipListNode<K> deleteNode, final SkipListNode<K>[] update) {
            for (int i = 0; i < this.level; i++) {
                if (update[i].levelInfo[i].forward == deleteNode) {
                    // 这些节点的高度小于等于要删除的节点，需要合并两个跨度
                    update[i].levelInfo[i].span += deleteNode.levelInfo[i].span - 1;
                    update[i].levelInfo[i].forward = deleteNode.levelInfo[i].forward;
                } else {
                    // 这些节点的高度高于要删除的节点，它们的跨度可以简单的 -1
                    update[i].levelInfo[i].span--;
                }
            }

            if (deleteNode.levelInfo[0].forward != null) {
                // 要删除的节点有后继节点
                deleteNode.levelInfo[0].forward.backward = deleteNode.backward;
            } else {
                // 要删除的节点是tail节点
                this.tail = deleteNode.backward;
            }

            // 如果删除的节点是最高等级的节点，则检查是否需要降级
            if (deleteNode.levelInfo.length == this.level) {
                while (this.level > 1 && this.header.levelInfo[this.level - 1].forward == null) {
                    // 如果最高层没有后继节点，则降级
                    this.level--;
                }
            }

            this.length--;
            this.modCount++;
        }

        /**
         * 判断zset中的数据所属的范围是否和指定range存在交集(intersection)。
         * 它不代表zset存在指定范围内的数据。
         * Returns if there is a part of the zset is in range.
         * <pre>
         *                         ZSet
         *              min ____________________ max
         *                 |____________________|
         *   min ______________ max  min _____________
         *      |______________|        |_____________|
         *          Range                   Range
         * </pre>
         *
         * @param range 范围描述信息
         * @return true/false
         */
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean zslIsInRange(ZLongScoreRangeSpec range) {
            if (isScoreRangeEmpty(range)) {
                // 传进来的范围为空
                return false;
            }

            if (this.tail == null || !zslValueGteMin(this.tail.score, range)) {
                // 列表有序，按照从score小到大，如果尾部节点数据小于最小值，那么一定不在区间范围内
                return false;
            }

            final SkipListNode firstNode = this.header.levelInfo[0].forward;
            if (firstNode == null || !zslValueLteMax(firstNode.score, range)) {
                // 列表有序，按照从score小到大，如果首部节点数据大于最大值，那么一定不在范围内
                return false;
            }
            return true;
        }

        /**
         * 测试score范围信息是否为空(无效)
         *
         * @param range 范围描述信息
         * @return true/false
         */
        private boolean isScoreRangeEmpty(ZLongScoreRangeSpec range) {
            // 这里和redis有所区别，这里min一定小于等于max
            return scoreEquals(range.min, range.max) && (range.minex || range.maxex);
        }

        /**
         * 找出第一个在指定范围内的节点。如果没有符合的节点，则返回null。
         * <p>
         * Find the first node that is contained in the specified range.
         * Returns NULL when no element is contained in the range.
         *
         * @param range 范围描述符
         * @return 不存在返回null
         */
        @Nullable
        SkipListNode<K> zslFirstInRange(ZLongScoreRangeSpec range) {
            /* zset数据范围与指定范围没有交集，可提前返回，减少不必要的遍历 */
            /* If everything is out of range, return early. */
            if (!zslIsInRange(range)) {
                return null;
            }

            SkipListNode<K> lastNodeLtMin = this.header;
            for (int i = this.level - 1; i >= 0; i--) {
                /* 前进直到出现后继节点大于等于指定最小值的节点 */
                /* Go forward while *OUT* of range. */
                while (lastNodeLtMin.levelInfo[i].forward != null &&
                        !zslValueGteMin(lastNodeLtMin.levelInfo[i].forward.score, range)) {
                    // 如果当前节点的后继节点仍然小于指定范围的最小值，则继续前进
                    lastNodeLtMin = lastNodeLtMin.levelInfo[i].forward;
                }
            }

            /* 这里的上下文表明了，一定存在一个节点的值大于等于指定范围的最小值，因此下一个节点一定不为null */
            /* This is an inner range, so the next node cannot be NULL. */
            final SkipListNode<K> firstNodeGteMin = lastNodeLtMin.levelInfo[0].forward;
            assert firstNodeGteMin != null;

            /* 如果该节点的数据大于max，则不存在再范围内的节点 */
            /* Check if score <= max. */
            if (!zslValueLteMax(firstNodeGteMin.score, range)) {
                return null;
            }
            return firstNodeGteMin;
        }

        /**
         * 找出最后一个在指定范围内的节点。如果没有符合的节点，则返回null。
         * <p>
         * Find the last node that is contained in the specified range.
         * Returns NULL when no element is contained in the range.
         *
         * @param range 范围描述信息
         * @return 不存在返回null
         */
        @Nullable
        SkipListNode<K> zslLastInRange(ZLongScoreRangeSpec range) {
            /* zset数据范围与指定范围没有交集，可提前返回，减少不必要的遍历 */
            /* If everything is out of range, return early. */
            if (!zslIsInRange(range)) {
                return null;
            }

            SkipListNode<K> lastNodeLteMax = this.header;
            for (int i = this.level - 1; i >= 0; i--) {
                /* Go forward while *IN* range. */
                while (lastNodeLteMax.levelInfo[i].forward != null &&
                        zslValueLteMax(lastNodeLteMax.levelInfo[i].forward.score, range)) {
                    // 如果当前节点的后继节点仍然小于最大值，则继续前进
                    lastNodeLteMax = lastNodeLteMax.levelInfo[i].forward;
                }
            }

            /* 这里的上下文表明一定存在一个节点的值小于指定范围的最大值，因此当前节点一定不为null */
            /* This is an inner range, so this node cannot be NULL. */
            assert lastNodeLteMax != null;

            /* Check if score >= min. */
            if (!zslValueGteMin(lastNodeLteMax.score, range)) {
                return null;
            }
            return lastNodeLteMax;
        }

        /**
         * 删除指定分数区间的所有节点。
         * <b>Note</b>: 该方法引用了ZSet的哈希表视图，以便从哈希表中删除成员。
         * <p>
         * Delete all the elements with score between min and max from the skiplist.
         * Min and max are inclusive, so a score >= min || score <= max is deleted.
         * Note that this function takes the reference to the hash table view of the
         * sorted set, in order to remove the elements from the hash table too.
         *
         * @param range 范围描述符
         * @param dict  对象id到score的映射
         * @return 删除的节点数量
         */
        int zslDeleteRangeByScore(ZLongScoreRangeSpec range, Map<K, Long> dict) {
//            final SkipListNode[] update = new SkipListNode[this.level];
            final SkipListNode<K>[] update = updateCache;
            try {
                int removed = 0;
                SkipListNode<K> lastNodeLtMin = this.header;
                for (int i = this.level - 1; i >= 0; i--) {
                    while (lastNodeLtMin.levelInfo[i].forward != null &&
                            !zslValueGteMin(lastNodeLtMin.levelInfo[i].forward.score, range)) {
                        lastNodeLtMin = lastNodeLtMin.levelInfo[i].forward;
                    }
                    update[i] = lastNodeLtMin;
                }

                /* 当前节点是小于目标范围最小值的最后一个节点，它的下一个节点可能为null，或大于等于最小值 */
                /* Current node is the last with score < or <= min. */
                SkipListNode<K> firstNodeGteMin = lastNodeLtMin.levelInfo[0].forward;

                /* 删除在范围内的节点(小于等于最大值的节点) */
                /* Delete nodes while in range. */
                while (firstNodeGteMin != null
                        && zslValueLteMax(firstNodeGteMin.score, range)) {
                    final SkipListNode<K> next = firstNodeGteMin.levelInfo[0].forward;
                    zslDeleteNode(firstNodeGteMin, update);
                    dict.remove(firstNodeGteMin.obj);
                    removed++;
                    firstNodeGteMin = next;
                }
                return removed;
            } finally {
                releaseUpdate(update);
            }
        }

        /**
         * 删除指定排名区间的所有成员。包括start和end。
         * <b>Note</b>: start和end基于从1开始
         * <p>
         * Delete all the elements with rank between start and end from the skiplist.
         * Start and end are inclusive. Note that start and end need to be 1-based
         *
         * @param start 起始排名 inclusive
         * @param end   截止排名 inclusive
         * @param dict  member -> score的字典
         * @return 删除的成员数量
         */
        int zslDeleteRangeByRank(int start, int end, Map<K, Long> dict) {
//            final SkipListNode[] update = new SkipListNode[this.level];
            final SkipListNode<K>[] update = updateCache;
            try {
                /* 已遍历的真实成员数量，表示成员的真实排名 */
                int traversed = 0;
                int removed = 0;

                SkipListNode<K> lastNodeLtStart = this.header;
                for (int i = this.level - 1; i >= 0; i--) {
                    while (lastNodeLtStart.levelInfo[i].forward != null &&
                            (traversed + lastNodeLtStart.levelInfo[i].span) < start) {
                        // 下一个节点的排名还未到范围内，继续前进
                        traversed += lastNodeLtStart.levelInfo[i].span;
                        lastNodeLtStart = lastNodeLtStart.levelInfo[i].forward;
                    }
                    update[i] = lastNodeLtStart;
                }

                traversed++;

                /* levelInfo[0] 最下面一层就是要删除节点的直接前驱 */
                SkipListNode<K> firstNodeGteStart = lastNodeLtStart.levelInfo[0].forward;
                while (firstNodeGteStart != null && traversed <= end) {
                    final SkipListNode<K> next = firstNodeGteStart.levelInfo[0].forward;
                    zslDeleteNode(firstNodeGteStart, update);
                    dict.remove(firstNodeGteStart.obj);
                    removed++;
                    traversed++;
                    firstNodeGteStart = next;
                }
                return removed;
            } finally {
                releaseUpdate(update);
            }
        }

        /**
         * 删除指定排名的成员 - 批量删除比单个删除更快捷
         * (该方法非原生方法)
         *
         * @param rank 排名 1-based
         * @param dict member -> score的字典
         * @return 删除的节点
         */
        SkipListNode<K> zslDeleteByRank(int rank, Map<K, Long> dict) {
//            final SkipListNode[] update = new SkipListNode[this.level];
            final SkipListNode<K>[] update = updateCache;
            try {
                int traversed = 0;

                SkipListNode<K> lastNodeLtStart = this.header;
                for (int i = this.level - 1; i >= 0; i--) {
                    while (lastNodeLtStart.levelInfo[i].forward != null &&
                            (traversed + lastNodeLtStart.levelInfo[i].span) < rank) {
                        // 下一个节点的排名还未到范围内，继续前进
                        traversed += lastNodeLtStart.levelInfo[i].span;
                        lastNodeLtStart = lastNodeLtStart.levelInfo[i].forward;
                    }
                    update[i] = lastNodeLtStart;
                }

                /* levelInfo[0] 最下面一层就是要删除节点的直接前驱 */
                final SkipListNode<K> targetRankNode = lastNodeLtStart.levelInfo[0].forward;
                if (null != targetRankNode) {
                    zslDeleteNode(targetRankNode, update);
                    dict.remove(targetRankNode.obj);
                    return targetRankNode;
                } else {
                    return null;
                }
            } finally {
                releaseUpdate(update);
            }
        }

        /**
         * 通过score和key查找成员所属的排名。
         * 如果找不到对应的成员，则返回0。
         * <b>Note</b>：排名从1开始
         * <p>
         * Find the rank for an element by both score and key.
         * Returns 0 when the element cannot be found, rank otherwise.
         * Note that the rank is 1-based due to the span of zsl->header to the
         * first element.
         *
         * @param score 节点分数
         * @param obj   节点对应的数据id
         * @return 排名，从1开始
         */
        int zslGetRank(long score, @Nonnull K obj) {
            int rank = 0;
            SkipListNode<K> firstNodeGteScore = this.header;
            for (int i = this.level - 1; i >= 0; i--) {
                while (firstNodeGteScore.levelInfo[i].forward != null &&
                        compareScoreAndObj(firstNodeGteScore.levelInfo[i].forward, score, obj) <= 0) {
                    // <= 也继续前进，也就是我们期望在目标节点停下来，这样rank也不必特殊处理
                    rank += firstNodeGteScore.levelInfo[i].span;
                    firstNodeGteScore = firstNodeGteScore.levelInfo[i].forward;
                }

                /* firstNodeGteScore might be equal to zsl->header, so test if firstNodeGteScore is header */
                if (firstNodeGteScore != this.header && objEquals(firstNodeGteScore.obj, obj)) {
                    // 可能在任意层找到
                    return rank;
                }
            }
            return 0;
        }

        /**
         * 查找指定排名的成员数据，如果不存在，则返回Null。
         * 注意：排名从1开始
         * <p>
         * Finds an element by its rank. The rank argument needs to be 1-based.
         *
         * @param rank 排名，1开始
         * @return element
         */
        @Nullable
        SkipListNode<K> zslGetElementByRank(int rank) {
            int traversed = 0;
            SkipListNode<K> firstNodeGteRank = this.header;
            for (int i = this.level - 1; i >= 0; i--) {
                while (firstNodeGteRank.levelInfo[i].forward != null &&
                        (traversed + firstNodeGteRank.levelInfo[i].span) <= rank) {
                    // <= rank 表示我们期望在目标节点停下来
                    traversed += firstNodeGteRank.levelInfo[i].span;
                    firstNodeGteRank = firstNodeGteRank.levelInfo[i].forward;
                }

                if (traversed == rank) {
                    // 可能在任意层找到该排名的数据
                    return firstNodeGteRank;
                }
            }
            return null;
        }

        /**
         * @return 跳表中的成员数量
         */
        private int length() {
            return length;
        }

        /**
         * 创建一个skipList的节点
         *
         * @param level 节点具有的层级 - {@link #zslRandomLevel()}
         * @param score 成员分数
         * @param obj   成员id
         * @return node
         */
        private static <K> SkipListNode<K> zslCreateNode(int level, long score, K obj) {
            final SkipListNode<K> node = new SkipListNode<>(obj, score, new SkipListLevel[level]);
            for (int index = 0; index < level; index++) {
                node.levelInfo[index] = new SkipListLevel<>();
            }
            return node;
        }

        /**
         * 返回一个随机的层级分配给即将插入的节点。
         * 返回的层级值在 1 和 ZSKIPLIST_MAXLEVEL 之间（包含两者）。
         * 具有类似幂次定律的分布，越高level返回的可能性更小。
         * <p>
         * Returns a random level for the new skiplist node we are going to create.
         * The return value of this function is between 1 and ZSKIPLIST_MAXLEVEL
         * (both inclusive), with a powerlaw-alike distribution where higher
         * levels are less likely to be returned.
         *
         * @return level
         */
        private int zslRandomLevel() {
            int level = 1;
            while (level < ZSKIPLIST_MAXLEVEL && random.nextFloat() < ZSKIPLIST_P) {
                level++;
            }
            return level;
        }

        /**
         * 计算两个score的和
         */
        private long sum(long score1, long score2) {
            return scoreHandler.sum(score1, score2);
        }

        /**
         * @param start 起始分数
         * @param end   截止分数
         * @return spec
         */
        private ZLongScoreRangeSpec newRangeSpec(long start, long end) {
            return newRangeSpec(start, false, end, false);
        }

        /**
         * @param rangeSpec 开放给用户的范围描述信息
         * @return spec
         */
        private ZLongScoreRangeSpec newRangeSpec(LongScoreRangeSpec rangeSpec) {
            return newRangeSpec(rangeSpec.getStart(), rangeSpec.isStartEx(), rangeSpec.getEnd(), rangeSpec.isEndEx());
        }

        /**
         * @param start   起始分数
         * @param startEx 是否去除起始分数
         * @param end     截止分数
         * @param endEx   是否去除截止分数
         * @return spec
         */
        private ZLongScoreRangeSpec newRangeSpec(long start, boolean startEx, long end, boolean endEx) {
            if (compareScore(start, end) <= 0) {
                return new ZLongScoreRangeSpec(start, startEx, end, endEx);
            } else {
                return new ZLongScoreRangeSpec(end, endEx, start, startEx);
            }
        }

        /**
         * 值是否大于等于下限
         *
         * @param value 要比较的score
         * @param spec  范围描述信息
         * @return true/false
         */
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean zslValueGteMin(long value, ZLongScoreRangeSpec spec) {
            return spec.minex ? compareScore(value, spec.min) > 0 : compareScore(value, spec.min) >= 0;
        }

        /**
         * 值是否小于等于上限
         *
         * @param value 要比较的score
         * @param spec  范围描述信息
         * @return true/false
         */
        boolean zslValueLteMax(long value, ZLongScoreRangeSpec spec) {
            return spec.maxex ? compareScore(value, spec.max) < 0 : compareScore(value, spec.max) <= 0;
        }

        /**
         * 比较score和key的大小，分数作为第一排序条件，然后，相同分数的成员按照字典规则相对排序
         *
         * @param forward 后继节点
         * @param score   分数
         * @param obj     成员的键
         * @return 0 表示equals
         */
        private int compareScoreAndObj(SkipListNode<K> forward, long score, K obj) {
            final int scoreCompareR = compareScore(forward.score, score);
            if (scoreCompareR != 0) {
                return scoreCompareR;
            }
            return compareObj(forward.obj, obj);
        }

        /**
         * 比较两个成员的key，<b>必须保证当且仅当两个键相等的时候返回0</b>
         * 字符串带有这样的特性。
         */
        private int compareObj(@Nonnull K objA, @Nonnull K objB) {
            return objComparator.compare(objA, objB);
        }

        /**
         * 判断两个对象是否相等，<b>必须保证当且仅当两个键相等的时候返回0</b>
         * 字符串带有这样的特性。
         *
         * @return true/false
         * @apiNote 使用compare == 0判断相等
         */
        private boolean objEquals(K objA, K objB) {
            // 不使用equals，而是使用compare
            return compareObj(objA, objB) == 0;
        }

        /**
         * 比较两个分数的大小
         *
         * @return 0表示相等
         */
        private int compareScore(long score1, long score2) {
            return scoreHandler.compare(score1, score2);
        }

        /**
         * 判断第一个分数是否和第二个分数相等
         *
         * @return true/false
         * @apiNote 使用compare == 0判断相等
         */
        private boolean scoreEquals(long score1, long score2) {
            return compareScore(score1, score2) == 0;
        }

        /**
         * 释放update引用的对象
         */
        private static <K> void releaseUpdate(SkipListNode<K>[] update) {
            for (int index = 0; index < ZSKIPLIST_MAXLEVEL; index++) {
                update[index] = null;
            }
        }

        /**
         * 重置rank中的数据
         */
        private static void releaseRank(int[] rank) {
            for (int index = 0; index < ZSKIPLIST_MAXLEVEL; index++) {
                rank[index] = 0;
            }
        }

        /**
         * 获取跳表的堆内存视图
         *
         * @return string
         */
        String dump() {
            final StringBuilder sb = new StringBuilder("{level = 0, nodeArray:[\n");
            SkipListNode<K> curNode = this.header.directForward();
            int rank = 0;
            while (curNode != null) {
                sb.append("{rank:").append(rank++)
                        .append(",obj:").append(curNode.obj)
                        .append(",score:").append(curNode.score);

                curNode = curNode.directForward();

                if (curNode != null) {
                    sb.append("},\n");
                } else {
                    sb.append("}\n");
                }
            }
            return sb.append("]}").toString();
        }

    }

    /**
     * 跳表节点
     */
    private static class SkipListNode<K> {
        /**
         * 节点对应的数据id
         */
        final K obj;
        /**
         * 该节点数据对应的评分 - 如果要通用的话，这里将来将是一个泛型对象，需要实现{@link Comparable}。
         */
        final long score;
        /**
         * 该节点的层级信息
         * level[]存放指向各层链表后一个节点的指针（后向指针）。
         */
        final SkipListLevel<K>[] levelInfo;
        /**
         * 该节点的前向指针
         * <b>NOTE:</b>(不包含header)
         * backward字段是指向链表前一个节点的指针（前向指针）。
         * 节点只有1个前向指针，所以只有第1层链表是一个双向链表。
         */
        SkipListNode<K> backward;

        private SkipListNode(K obj, long score, SkipListLevel[] levelInfo) {
            this.obj = obj;
            this.score = score;
            // noinspection unchecked
            this.levelInfo = levelInfo;
        }

        /**
         * @return 该节点的直接后继节点
         */
        SkipListNode<K> directForward() {
            return levelInfo[0].forward;
        }
    }

    /**
     * 跳表层级
     */
    private static class SkipListLevel<K> {
        /**
         * 每层对应1个后向指针 (后继节点)
         */
        SkipListNode<K> forward;
        /**
         * 到后继节点之间的跨度
         * 它表示当前的指针跨越了多少个节点。span用于计算成员排名(rank)，这是Redis对于SkipList做的一个扩展。
         */
        int span;
    }

    // region 迭代

    /**
     * ZSet迭代器
     * Q: 为什么不写在{@link SkipList}中？
     * A: 因为删除数据需要访问{@link #dict}。
     */
    private class ZSetItr implements Iterator<Obj2LongMember<K>> {

        private SkipListNode<K> lastReturned;
        private SkipListNode<K> next;
        int expectedModCount = zsl.modCount;

        ZSetItr(SkipListNode<K> next) {
            this.next = next;
        }

        public boolean hasNext() {
            return next != null;
        }

        public Obj2LongMember<K> next() {
            checkForComodification();

            if (next == null) {
                throw new NoSuchElementException();
            }

            lastReturned = next;
            next = next.directForward();

            return new Obj2LongMember<>(lastReturned.obj, lastReturned.score);
        }

        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }

            checkForComodification();

            // remove lastReturned
            dict.remove(lastReturned.obj);
            zsl.zslDelete(lastReturned.score, lastReturned.obj);

            // reset lastReturned
            lastReturned = null;
            expectedModCount = zsl.modCount;
        }

        final void checkForComodification() {
            if (zsl.modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }
    }
    // endregion
}