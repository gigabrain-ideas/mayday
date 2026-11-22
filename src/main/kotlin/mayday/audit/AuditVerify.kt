package mayday.audit

import java.io.File

/**
 * `java -jar mayday.jar audit-verify <file>... | -`: checks an export of JSON lines (Loki, Sentinel, file sink).
 * Key: MAYDAY_AUDIT_KEY, else derived from MAYDAY_SECRET_KEY. Exit code 0 = intact, 1 = problems, 2 = usage.
 */
object AuditVerify {
    fun main(args: Array<String>, env: Map<String, String> = System.getenv(), out: java.io.PrintStream = System.out): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: mayday audit-verify <file>... (or - for standard input). Set MAYDAY_AUDIT_KEY or MAYDAY_SECRET_KEY.")
            return 2
        }
        val auditKey = env["MAYDAY_AUDIT_KEY"].orEmpty()
        val secret = env["MAYDAY_SECRET_KEY"].orEmpty()
        if (auditKey.isEmpty() && secret.isEmpty()) {
            System.err.println("Set MAYDAY_AUDIT_KEY or MAYDAY_SECRET_KEY to the value of the instances that wrote the stream.")
            return 2
        }
        val lines = args.asSequence().flatMap { if (it == "-") System.`in`.bufferedReader().lineSequence() else File(it).bufferedReader().lineSequence() }
        val report = AuditChain.verify(lines, AuditChain.key(secret, auditKey))
        out.println("${report.records} records, ${report.instances.size} instances, ${report.unreadable} unreadable lines")
        report.instances.forEach { (i, r) -> out.println("  $i: sequence ${r.first} to ${r.last}") }
        report.problems.forEach { out.println("PROBLEM ${it.instance} #${it.seq}: ${it.problem}") }
        out.println(if (report.ok) "OK: the chain is intact." else "FAILED: ${report.problems.size} problems.")
        return if (report.ok) 0 else 1
    }
}
