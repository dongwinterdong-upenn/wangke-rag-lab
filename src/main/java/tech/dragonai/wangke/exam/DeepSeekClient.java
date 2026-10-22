package tech.dragonai.wangke.exam;

/**
 * 叙述层用的模型调用面（{@code deepseek-v4-flash}）。
 *
 * <h3>为什么是接口</h3>
 * 报告这条链上真正难的判据全在<b>调用之后</b>：半截回包要不要落库、正文里的时间点对不对得上、
 * 抢占之后失败了状态怎么收。这些判据必须能在<b>不打真实 DeepSeek</b> 的前提下逐条钉住 ——
 * 真打一次要 19 秒、要花钱、而且它每次写的话都不一样，负例根本构造不出来
 * （"让模型写一个不存在的时间点"这件事没法稳定复现）。所以调用面抽成接口，
 * 生产装 {@link DeepSeekHttpClient}，判据装脚本化的假客户端。
 *
 * <h3>返回的是"完整正文"或"失败理由"，没有第三种</h3>
 * {@link Reply} 里 content 与 failReason 恰有一个非空。<b>半截正文一律算失败</b>：
 * 它读起来和完整的一模一样通顺，只是后半段的行动清单没了 ——
 * 这是所有失败形态里最坏的一种，因为没有任何人会发现。
 */
public interface DeepSeekClient {

    /**
     * 一次生成的结果。
     *
     * @param content    完整正文；失败时为空
     * @param model      真正回应的模型 id，落库留痕（同一份报告日后要能说清是谁写的）
     * @param failReason 失败原因，<b>要给学员看</b>，所以是人话不是异常字符串；成功时为空
     */
    record Reply(String content, String model, String failReason) {

        public boolean ok() { return failReason == null; }

        public static Reply of(String content, String model) {
            return new Reply(content, model, null);
        }

        /** 失败必须带理由：报告状态置 FAILED 时库层触发器要求 fail_reason 非空。 */
        public static Reply fail(String why) {
            return new Reply(null, null, why == null || why.isBlank() ? "模型调用失败" : why);
        }
    }

    /**
     * 同步生成一次。<b>不抛异常</b> —— 网络、超时、非 200、半截回包统统收敛成
     * {@link Reply#fail}，因为调用方要做的事在每种失败下都一样：把状态置 FAILED 并写明理由。
     */
    Reply complete(String systemPrompt, String userPrompt);
}
