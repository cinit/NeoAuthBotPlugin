package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.res.ResImpl
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.User
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedText
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedTextBuilder

@Suppress("unused")
object LocaleHelper {

    internal var discussionGroupLink: String? = null

    fun createFormattedMsgText(
        info: SessionManager.UserAuthSession,
        user: User,
        maxDuration: Int
    ): FormattedText {
        val r = ResImpl.getResourceForUser(user)
        val selectedNames = ArrayList<String>(1)
        for (i in info.selectedRegion) {
            val x = i shr 4
            val y = i and 0x0f
            selectedNames.add(StringBuilder().apply {
                appendCodePoint((('A' + x).code))
                appendCodePoint((('1' + y).code))
            }.toString())
        }
        val msg = FormattedTextBuilder().apply {
            this + r.auth_instruction_part1 + user.name + r.auth_instruction_part2 +
                    Underline(maxDuration.toString()) + r.auth_instruction_part3 + "\n" +
                    Bold(r.auth_ins_fail_on_timeout) + "\n" +
                    r.auth_change_quiz_chances_left_part1 +
                    Underline(info.changesAllowed.toString()) +
                    r.auth_change_quiz_chances_left_part2 + "\n" +
                    r.auth_hint_all_region_count_part1 +
                    Underline(info.actualChiralRegion.size.toString()) +
                    r.auth_hint_all_region_count_part2 + "\n" +
                    r.auth_current_selected_regions +
                    Underline(
                        if (selectedNames.isEmpty()) r.auth_none_selected
                        else selectedNames.joinToString(", ")
                    )
        }.build()
        return msg
    }

    fun getBotHelpInfoFormattedText(bot: Bot, user: User): FormattedText {
        val r = ResImpl.getResourceForUser(user)
        return FormattedTextBuilder().apply {
            this + Bold(r.help_info_category_auth) + "\n" +
                    r.help_info_category_auth_description + "\n\n" +
                    Bold(r.help_info_category_test) + "\n" +
                    r.help_info_category_test_description + "\n\n" +
                    Bold(r.help_info_category_other) + "\n" +
                    r.help_info_category_other_description + "\n\n" +
                    r.format(r.help_info_require_at_in_group_va1, "/config@${bot.username}")
        }.build()
    }

    fun getBotAboutInfoFormattedText(user: User): FormattedText {
        val r = ResImpl.getResourceForUser(user)
        return FormattedTextBuilder().apply {
            this + Bold("NeoAuth3Bot") + "\n" +
                    r.help_about_desc1_part1 +
                    TextUrl("TeleBotConsole", "https://github.com/cinit/TeleBotConsole") +
                    r.help_about_desc1_part2 +
                    TextUrl("PubChem", "https://pubchem.ncbi.nlm.nih.gov/") + "\n" +
                    r.help_about_desc2_part1 +
                    "https://github.com/cinit/NeoAuthBotPlugin" +
                    r.help_about_desc2_part2 + "\n" +
                    r.help_about_desc3 +
                    if (!discussionGroupLink.isNullOrEmpty()) {
                        "\n" + r.format(r.help_about_discussion_group_link_va1, discussionGroupLink!!)
                    } else ""
        }.build()
    }
}
