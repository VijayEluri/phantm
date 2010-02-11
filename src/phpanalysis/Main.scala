package phpanalysis;

import phpanalysis.parser._;
import phpanalysis.analyzer._;
import phpanalysis.controlflow._;
import phpanalysis.parser.Trees.Program;
import java.io._;
import Math.max;

object Main {
    var files: List[String] = Nil;
    var displaySymbols     = false;
    var verbosity          = 1;
    var displayDebug       = false;
    var displayProgress    = false;
    var onlyLint           = false;
    var includePaths       = List(".");
    var apis: List[String] = Nil;

    def main(args: Array[String]): Unit = {
        if (args.length > 0) {
            handleArgs(args.toList);

            if (files.length == 0) {
                println("No file provided.")
                usage
            } else {
                compile(files)
            }
        } else {
            usage
        }
    }

    def handleArgs(args: List[String]): Unit = args match {
        case "--symbols" :: xs =>
            displaySymbols = true
            handleArgs(xs)
        case "--debug" :: xs =>
            displayDebug = true
            handleArgs(xs)
        case "--verbose" :: xs =>
            verbosity = max(verbosity, 2)
            handleArgs(xs)
        case "--vverbose" :: xs =>
            verbosity = max(verbosity, 3)
            handleArgs(xs)
        case "--includepath" :: ip :: xs =>
            includePaths = ip.split(":").toList
            handleArgs(xs)
        case "--apis" :: aps :: xs =>
            apis = aps.split(":").toList
            handleArgs(xs)
        case "--progress" :: xs =>
            displayProgress = true
            handleArgs(xs)
        case "--lint" ::  xs =>
            onlyLint = true
            handleArgs(xs)
        case x :: xs =>
            files = files ::: x :: Nil
            handleArgs(xs)
        case Nil =>
    }

    def compile(files: List[String]) = {
        try {
            if (displayProgress) println("1/7 Compiling...")
            val sts = files map { f => val c = new Compiler(f); (c, c compile) }
            if (sts exists { _._2 == None} ) {
                println("Compilation failed.")
            } else if(!onlyLint) {
                if (displayProgress) println("2/7 Simplifying...")
                val asts = sts map { c => new STToAST(c._1, c._2.get) getAST }
                Reporter.errorMilestone
                var ast: Program = asts.reduceLeft {(a,b) => a combine b}
                Reporter.errorMilestone

                if (displayProgress) println("3/7 Resolving and expanding...")
                // Run AST transformers
                ast = IncludeResolver(ast).transform
                if (displayProgress) println("4/7 Structural checks...")
                // Traverse the ast to look for ovious mistakes.
                new ASTChecks(ast) execute;
                Reporter.errorMilestone

                if (displayProgress) println("5/7 Importing APIs...")
                // Load internal classes and functions into the symbol tables
                new API("spec/internal_api.xml").load

                for (api <-apis) {
                    new API(api).load
                }

                Reporter.errorMilestone

                if (displayProgress) println("6/7 Symbolic checks...")
                // Collect symbols and detect obvious types errors
                CollectSymbols(ast) execute;
                Reporter.errorMilestone

                if (displaySymbols) {
                    // Emit summary of all symbols
                    analyzer.Symbols.emitSummary
                }

                if (displayProgress) println("7/7 Type flow analysis...")
                // Build CFGs and analyzes them
                CFGChecks(ast) execute;
                Reporter.errorMilestone

                val n = Reporter.getNoticesCount
                if (n > 0) {
                    println(n+" notice"+(if (n>1) "s" else "")+" occured.")
                }
            } else {
                println("Compilation succeeded.")
            }
        } catch {
            case Reporter.ErrorException(en, nn) =>
                println(nn+" notice"+(if (nn>1) "s" else "")+" and "+en+" error"+(if (en>1) "s" else "")+" occured, abort.")
        }
    }

    def usage = {
        println("Usage:   phpanalysis [..options..] <files ...>");
        println("Options: --symbols              Display symbols");
        println("         --debug                Debug information");
        println("         --verbose              Be more strict");
        println("         --vverbose             Be nitpicking");
        println("         --includepath <paths>  Define paths for compile time include resolution (.:a:bb:c:..)");
        println("         --apis <paths>         Import APIs prior to the analysis (a.xml:b.xml:...)");
        println("         --progress             Display analysis progress");
    }
}
